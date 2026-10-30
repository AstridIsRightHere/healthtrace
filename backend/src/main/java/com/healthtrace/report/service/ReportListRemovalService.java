package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 列表移除（回收站）与恢复（FR-016 / DEC-002）。
 * <ul>
 *   <li>移除：NORMAL → LIST_REMOVED，任意处理状态均可；识别中的报告不中断 OCR，结果照常落为草稿，
 *       报告仍留在回收站，恢复后出现在待处理列表。已在回收站时幂等返回成功（不重复审计）。</li>
 *   <li>恢复：LIST_REMOVED → NORMAL；报告本就正常时 409 HT-4002。</li>
 *   <li>两者均版本 +1、刷新 updated_at（回收站以 updated_at 作为移除时间）。</li>
 *   <li>已删除、不存在或越权统一 404 HT-3001。</li>
 * </ul>
 */
@Service
public class ReportListRemovalService {

    private final ReportAccess access;
    private final ReportMapper reportMapper;
    private final AuditService auditService;
    private final Clock clock;

    public ReportListRemovalService(ReportAccess access, ReportMapper reportMapper, AuditService auditService,
                                    Clock clock) {
        this.access = access;
        this.reportMapper = reportMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public void remove(long accountId, long reportId) {
        Report report = access.requireOwnedReportAnyVisibility(accountId, reportId);
        if (report.getVisibilityStatus() == VisibilityStatus.LIST_REMOVED) {
            return;
        }
        if (reportMapper.changeVisibility(reportId, VisibilityStatus.NORMAL, VisibilityStatus.LIST_REMOVED,
                now()) == 0) {
            // 校验后状态被并发改变：已被移除则视为成功，否则（删除中/已删除）按不存在处理
            requireCurrent(reportId, VisibilityStatus.LIST_REMOVED, ErrorCode.RESOURCE_NOT_FOUND);
            return;
        }
        auditService.success(accountId, AuditAction.REPORT_LIST_REMOVE, AuditService.token("report", reportId));
    }

    @Transactional
    public void restore(long accountId, long reportId) {
        Report report = access.requireOwnedReportAnyVisibility(accountId, reportId);
        if (report.getVisibilityStatus() != VisibilityStatus.LIST_REMOVED) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT, "报告不在回收站中");
        }
        if (reportMapper.changeVisibility(reportId, VisibilityStatus.LIST_REMOVED, VisibilityStatus.NORMAL,
                now()) == 0) {
            // 并发：已被恢复 → 409；已删除 → 404
            requireCurrent(reportId, VisibilityStatus.NORMAL, ErrorCode.RESOURCE_NOT_FOUND);
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT, "报告不在回收站中");
        }
        auditService.success(accountId, AuditAction.REPORT_LIST_RESTORE, AuditService.token("report", reportId));
    }

    private void requireCurrent(long reportId, VisibilityStatus expected, ErrorCode otherwise) {
        Report current = reportMapper.findById(reportId);
        if (current == null || current.getVisibilityStatus() != expected) {
            throw new ApiException(otherwise);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
