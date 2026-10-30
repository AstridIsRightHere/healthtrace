package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 彻底删除本人名下的报告（FR-016），任意处理状态均可删除，含已确认与识别中。
 * <ul>
 *   <li>归属校验后交给 {@link ReportPurger}：同一事务内删除修改记录、就诊关联、结果、OCR 任务、图片与报告，
 *       并写入 REPORT_PURGE 账本与存储清理 outbox。</li>
 *   <li>识别中的报告：任务行随报告删除，执行中的 OCR 提交结果时因行不存在而放弃写入。</li>
 *   <li>存储对象在提交后异步清理，失败由定时任务重试，不回滚业务删除。</li>
 * </ul>
 */
@Service
public class ReportDeletionService {

    private final ReportAccess access;
    private final ReportPurger purger;
    private final AuditService auditService;

    public ReportDeletionService(ReportAccess access, ReportPurger purger, AuditService auditService) {
        this.access = access;
        this.purger = purger;
        this.auditService = auditService;
    }

    @Transactional
    public void delete(long accountId, long reportId) {
        access.requireOwnedReport(accountId, reportId);
        if (purger.purge(reportId) == 0) {
            // 校验后被并发删除
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        auditService.success(accountId, AuditAction.REPORT_DELETE, AuditService.token("report", reportId));
    }
}
