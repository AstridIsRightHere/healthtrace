package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.report.domain.Report;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FR-016：归属校验 → ReportPurger → 审计；越权与并发删除统一 404。 */
class ReportDeletionServiceTest {

    private ReportAccess access;
    private ReportPurger purger;
    private AuditService auditService;
    private ReportDeletionService service;

    @BeforeEach
    void setUp() {
        access = mock(ReportAccess.class);
        purger = mock(ReportPurger.class);
        auditService = mock(AuditService.class);
        service = new ReportDeletionService(access, purger, auditService);
    }

    @Test
    void othersReportIsNotFoundAndNothingDeleted() {
        willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).given(access).requireOwnedReport(7L, 99L);
        assertThatThrownBy(() -> service.delete(7L, 99L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(purger, never()).purge(anyLong());
        verify(auditService, never()).success(any(), any(), anyString());
    }

    @Test
    void ownedReportIsPurgedThenAudited() {
        given(access.requireOwnedReport(7L, 21L)).willReturn(new Report());
        given(purger.purge(21L)).willReturn(5);

        service.delete(7L, 21L);

        var order = inOrder(access, purger, auditService);
        order.verify(access).requireOwnedReport(7L, 21L);
        order.verify(purger).purge(21L);
        order.verify(auditService).success(7L, AuditAction.REPORT_DELETE, "report:21");
    }

    @Test
    void concurrentlyDeletedReportIsNotFound() {
        given(access.requireOwnedReport(7L, 21L)).willReturn(new Report());
        given(purger.purge(21L)).willReturn(0);

        assertThatThrownBy(() -> service.delete(7L, 21L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(auditService, never()).success(any(), any(), anyString());
    }
}
