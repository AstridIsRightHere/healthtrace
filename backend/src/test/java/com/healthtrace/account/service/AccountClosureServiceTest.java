package com.healthtrace.account.service;

import com.healthtrace.account.domain.Account;
import com.healthtrace.account.domain.AccountStatus;
import com.healthtrace.account.mapper.AccountClosureMapper;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.report.service.ReportPurger;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import com.healthtrace.rights.deletion.DeletionEventType;
import com.healthtrace.rights.deletion.DeletionLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 账号注销服务单元测试（FR-017）：密码校验、独占/共享档案处置顺序、台账与审计。 */
class AccountClosureServiceTest {

    private static final long ACCOUNT_ID = 7L;
    private static final String PASSWORD = "correct horse battery";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private AccountClosureMapper closureMapper;
    private ReportPurger reportPurger;
    private DeletionLedgerService ledgerService;
    private AuditService auditService;
    private AccountClosureService service;

    @BeforeEach
    void setUp() {
        closureMapper = mock(AccountClosureMapper.class);
        reportPurger = mock(ReportPurger.class);
        ledgerService = mock(DeletionLedgerService.class);
        auditService = mock(AuditService.class);
        service = new AccountClosureService(closureMapper, reportPurger, ledgerService, auditService, encoder);
    }

    private void givenAccount() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setPasswordHash(encoder.encode(PASSWORD));
        account.setStatus(AccountStatus.ACTIVE);
        given(closureMapper.lockAccount(ACCOUNT_ID)).willReturn(account);
    }

    @Test
    void wrongPasswordIsRejectedBeforeAnyDeletion() {
        givenAccount();

        assertThatThrownBy(() -> service.close(ACCOUNT_ID, "wrong password"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_CREDENTIALS);

        verify(closureMapper, never()).findAccessibleProfileIds(anyLong());
        verify(closureMapper, never()).deleteAccount(anyLong());
        verifyNoInteractions(reportPurger, ledgerService, auditService);
    }

    @Test
    void nullPasswordIsRejected() {
        givenAccount();

        assertThatThrownBy(() -> service.close(ACCOUNT_ID, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_CREDENTIALS);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void missingAccountIsTreatedAsUnauthenticated() {
        given(closureMapper.lockAccount(ACCOUNT_ID)).willReturn(null);

        assertThatThrownBy(() -> service.close(ACCOUNT_ID, PASSWORD))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        verifyNoInteractions(reportPurger, ledgerService, auditService);
    }

    @Test
    void exclusiveProfileIsPurgedInOrderThenAccountDeletedLast() {
        givenAccount();
        given(closureMapper.findAccessibleProfileIds(ACCOUNT_ID)).willReturn(List.of(100L));
        given(closureMapper.countOtherAccessHolders(100L, ACCOUNT_ID)).willReturn(0);
        given(closureMapper.findReportIdsByProfile(100L)).willReturn(List.of(1L, 2L));
        given(reportPurger.purge(1L)).willReturn(5);
        given(reportPurger.purge(2L)).willReturn(3);
        given(closureMapper.deleteVisitsByProfile(100L)).willReturn(1);
        given(closureMapper.deleteFollowedMetricsByProfile(100L)).willReturn(6);
        given(closureMapper.deleteConsentsByProfile(100L)).willReturn(2);
        given(closureMapper.deleteAccessByProfile(100L)).willReturn(1);
        given(closureMapper.deleteProfile(100L)).willReturn(1);
        given(closureMapper.deleteAuthTokens(ACCOUNT_ID)).willReturn(2);
        given(closureMapper.deleteIssuedInvitations(ACCOUNT_ID)).willReturn(1);
        given(closureMapper.deleteAccount(ACCOUNT_ID)).willReturn(1);

        int removed = service.close(ACCOUNT_ID, PASSWORD);

        // 5+3 报告 + 1 就诊 + 6 关注 + 2 同意 + 1 授权 + 1 档案 + 2 令牌 + 1 邀请 + 1 账号
        assertThat(removed).isEqualTo(23);
        InOrder order = inOrder(closureMapper, reportPurger, ledgerService, auditService);
        order.verify(closureMapper).lockAccount(ACCOUNT_ID);
        order.verify(reportPurger).purge(1L);
        order.verify(reportPurger).purge(2L);
        order.verify(closureMapper).deleteVisitsByProfile(100L);
        order.verify(closureMapper).deleteFollowedMetricsByProfile(100L);
        order.verify(closureMapper).deleteConsentsByProfile(100L);
        order.verify(closureMapper).deleteAccessByProfile(100L);
        order.verify(closureMapper).deleteProfile(100L);
        order.verify(closureMapper).countRemainingReferences(ACCOUNT_ID);
        order.verify(closureMapper).deleteAuthTokens(ACCOUNT_ID);
        order.verify(closureMapper).deleteIssuedInvitations(ACCOUNT_ID);
        order.verify(closureMapper).clearInvitationUsage(ACCOUNT_ID);
        order.verify(closureMapper).deleteAccount(ACCOUNT_ID);
        order.verify(ledgerService).record(DeletionEventType.ACCOUNT_CLOSURE, ACCOUNT_ID, 23);
        order.verify(auditService).success(ACCOUNT_ID, AuditAction.ACCOUNT_CLOSE, "account:" + ACCOUNT_ID);
    }

    @Test
    void sharedProfileKeepsDataAndOnlyRevokesOwnAccess() {
        givenAccount();
        given(closureMapper.findAccessibleProfileIds(ACCOUNT_ID)).willReturn(List.of(200L));
        given(closureMapper.countOtherAccessHolders(200L, ACCOUNT_ID)).willReturn(1);

        service.close(ACCOUNT_ID, PASSWORD);

        verify(closureMapper, never()).findReportIdsByProfile(200L);
        verify(closureMapper, never()).deleteProfile(200L);
        verifyNoInteractions(reportPurger);
        verify(closureMapper).deleteAccessByAccount(ACCOUNT_ID);
        verify(closureMapper).deleteConsentsByAccount(ACCOUNT_ID);
        verify(closureMapper).deleteAccount(ACCOUNT_ID);
    }

    @Test
    void remainingForeignKeyReferencesAbortClosure() {
        givenAccount();
        given(closureMapper.findAccessibleProfileIds(ACCOUNT_ID)).willReturn(List.of(200L));
        given(closureMapper.countOtherAccessHolders(200L, ACCOUNT_ID)).willReturn(1);
        given(closureMapper.countRemainingReferences(ACCOUNT_ID)).willReturn(3);

        assertThatThrownBy(() -> service.close(ACCOUNT_ID, PASSWORD))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.OPERATION_NOT_ALLOWED);
        verify(closureMapper, never()).deleteAccount(anyLong());
        verify(ledgerService, never()).record(any(), anyLong(), anyInt());
        verifyNoInteractions(auditService);
    }

    @Test
    void accountWithoutProfilesStillWritesLedgerAndAudit() {
        givenAccount();
        given(closureMapper.findAccessibleProfileIds(ACCOUNT_ID)).willReturn(List.of());
        given(closureMapper.deleteAccount(ACCOUNT_ID)).willReturn(1);

        int removed = service.close(ACCOUNT_ID, PASSWORD);

        assertThat(removed).isEqualTo(1);
        verify(ledgerService).record(DeletionEventType.ACCOUNT_CLOSURE, ACCOUNT_ID, 1);
        verify(auditService).success(ACCOUNT_ID, AuditAction.ACCOUNT_CLOSE, "account:" + ACCOUNT_ID);
    }
}
