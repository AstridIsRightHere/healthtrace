package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FR-016 列表移除 / 恢复：状态切换、幂等、冲突与并发分支。 */
class ReportListRemovalServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 10, 0);

    private ReportAccess access;
    private ReportMapper reportMapper;
    private AuditService auditService;
    private ReportListRemovalService service;

    @BeforeEach
    void setUp() {
        access = mock(ReportAccess.class);
        reportMapper = mock(ReportMapper.class);
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00.789Z"), ZoneOffset.UTC);
        service = new ReportListRemovalService(access, reportMapper, auditService, clock);
    }

    private static Report report(VisibilityStatus visibility) {
        Report r = new Report();
        r.setId(21L);
        r.setVisibilityStatus(visibility);
        return r;
    }

    @Test
    void removeNormalReportSwitchesVisibilityAndAudits() {
        given(access.requireOwnedReportAnyVisibility(7L, 21L)).willReturn(report(VisibilityStatus.NORMAL));
        given(reportMapper.changeVisibility(21L, VisibilityStatus.NORMAL, VisibilityStatus.LIST_REMOVED, NOW))
                .willReturn(1);

        service.remove(7L, 21L);

        verify(reportMapper).changeVisibility(21L, VisibilityStatus.NORMAL, VisibilityStatus.LIST_REMOVED, NOW);
        verify(auditService).success(7L, AuditAction.REPORT_LIST_REMOVE, "report:21");
    }

    @Test
    void removeAlreadyRemovedIsIdempotentWithoutAudit() {
        given(access.requireOwnedReportAnyVisibility(7L, 21L)).willReturn(report(VisibilityStatus.LIST_REMOVED));

        service.remove(7L, 21L);

        verify(reportMapper, never()).changeVisibility(anyLong(), any(), any(), any());
        verify(auditService, never()).success(any(), any(), anyString());
    }

    @Test
    void removeOthersReportIsNotFound() {
        willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).given(access).requireOwnedReportAnyVisibility(7L, 21L);

        assertThatThrownBy(() -> service.remove(7L, 21L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(reportMapper, never()).changeVisibility(anyLong(), any(), any(), any());
    }

    @Test
    void removeRacingWithPurgeIsNotFound() {
        given(access.requireOwnedReportAnyVisibility(7L, 21L)).willReturn(report(VisibilityStatus.NORMAL));
        given(reportMapper.changeVisibility(21L, VisibilityStatus.NORMAL, VisibilityStatus.LIST_REMOVED, NOW))
                .willReturn(0);
        given(reportMapper.findById(21L)).willReturn(null);

        assertThatThrownBy(() -> service.remove(7L, 21L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(auditService, never()).success(any(), any(), anyString());
    }

    @Test
    void removeRacingWithAnotherRemoveSucceedsSilently() {
        given(access.requireOwnedReportAnyVisibility(7L, 21L)).willReturn(report(VisibilityStatus.NORMAL));
        given(reportMapper.changeVisibility(21L, VisibilityStatus.NORMAL, VisibilityStatus.LIST_REMOVED, NOW))
                .willReturn(0);
        given(reportMapper.findById(21L)).willReturn(report(VisibilityStatus.LIST_REMOVED));

        service.remove(7L, 21L);

        verify(auditService, never()).success(any(), any(), anyString());
    }

    @Test
    void restoreRemovedReportSwitchesBackAndAudits() {
        given(access.requireOwnedReportAnyVisibility(7L, 21L)).willReturn(report(VisibilityStatus.LIST_REMOVED));
        given(reportMapper.changeVisibility(21L, VisibilityStatus.LIST_REMOVED, VisibilityStatus.NORMAL, NOW))
                .willReturn(1);

        service.restore(7L, 21L);

        verify(auditService).success(7L, AuditAction.REPORT_LIST_RESTORE, "report:21");
    }

    @Test
    void restoreNormalReportIsConflict() {
        given(access.requireOwnedReportAnyVisibility(7L, 21L)).willReturn(report(VisibilityStatus.NORMAL));

        assertThatThrownBy(() -> service.restore(7L, 21L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.REPORT_STATE_CONFLICT);
        verify(reportMapper, never()).changeVisibility(anyLong(), any(), any(), any());
        verify(auditService, never()).success(any(), any(), anyString());
    }

    @Test
    void restoreRacingWithAnotherRestoreIsConflict() {
        given(access.requireOwnedReportAnyVisibility(7L, 21L)).willReturn(report(VisibilityStatus.LIST_REMOVED));
        given(reportMapper.changeVisibility(21L, VisibilityStatus.LIST_REMOVED, VisibilityStatus.NORMAL, NOW))
                .willReturn(0);
        given(reportMapper.findById(21L)).willReturn(report(VisibilityStatus.NORMAL));

        assertThatThrownBy(() -> service.restore(7L, 21L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.REPORT_STATE_CONFLICT);
    }
}
