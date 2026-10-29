package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.profile.domain.ProfileRecord;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.OcrJob;
import com.healthtrace.report.domain.OcrJobStatus;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.mapper.OcrJobMapper;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.ocr.OcrService;
import com.healthtrace.report.service.ReviewViews.DraftReport;
import com.healthtrace.report.service.ReviewViews.OcrJobView;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import com.healthtrace.rights.consent.ConsentService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;

/**
 * 进入核对前的处理入口（FR-006、FR-014）：提交第三方 OCR、查询识别任务、转手动录入。
 * <ul>
 *   <li>提交识别必须显式同意第三方 OCR（NFR-002），否则 403 HT-4003，不会调用供应商。</li>
 *   <li>任务在事务提交后异步执行；同一报告只有一个活动任务。</li>
 * </ul>
 * 草稿查看与整页确认见 {@link ReviewConfirmService}。
 */
@Service
public class OcrSubmissionService {

    static final String NO_COMPLETE_PHOTO = "报告还没有上传完成的照片";

    private static final Set<ProcessingStatus> SUBMITTABLE =
            EnumSet.of(ProcessingStatus.UPLOADING, ProcessingStatus.FAILED);

    private final ReportAccess access;
    private final ReportMapper reportMapper;
    private final PhotoMapper photoMapper;
    private final OcrJobMapper ocrJobMapper;
    private final OcrService ocrService;
    private final OcrJobProcessor processor;
    private final ProfileMapper profileMapper;
    private final ConsentService consentService;
    private final AuditService auditService;
    private final Clock clock;

    public OcrSubmissionService(ReportAccess access, ReportMapper reportMapper, PhotoMapper photoMapper,
                                OcrJobMapper ocrJobMapper, OcrService ocrService, OcrJobProcessor processor,
                                ProfileMapper profileMapper, ConsentService consentService,
                                AuditService auditService, Clock clock) {
        this.access = access;
        this.reportMapper = reportMapper;
        this.photoMapper = photoMapper;
        this.ocrJobMapper = ocrJobMapper;
        this.ocrService = ocrService;
        this.processor = processor;
        this.profileMapper = profileMapper;
        this.consentService = consentService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * 创建 OCR 任务。幂等：报告已在识别中时返回当前活动任务。
     * 顺序：归属校验 → 同意校验 → 邮箱验证 → 状态/照片校验 → 落库 → 提交后异步执行。
     */
    @Transactional
    public OcrJobView submitOcr(long accountId, long reportId, boolean ocrConsent) {
        access.requireOwnedReport(accountId, reportId);
        if (!ocrConsent) {
            throw new ApiException(ErrorCode.OCR_CONSENT_REQUIRED);
        }
        access.requireVerifiedEmail(accountId);

        Report report = reportMapper.findByIdForUpdate(reportId);
        if (report.getProcessingStatus() == ProcessingStatus.RECOGNIZING) {
            OcrJob active = ocrJobMapper.findLatestByReport(reportId);
            if (active != null && active.getStatus().isActive()) {
                return OcrJobView.of(active);
            }
        }
        if (!SUBMITTABLE.contains(report.getProcessingStatus())) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT);
        }
        if (photoMapper.findCompleteByReport(reportId).isEmpty()) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT, NO_COMPLETE_PHOTO);
        }

        LocalDateTime now = now();
        // 本次发送第三方的特定同意：持久化完整版本记录，任务引用其 id（与任务同事务提交/回滚）
        ProfileRecord profile = profileMapper.findActiveByAccountAndProfile(accountId, report.getProfileId());
        long consentId = consentService.grantOcr(accountId, report.getProfileId(),
                profile == null ? null : profile.getRelationship(), now);
        OcrJob job = new OcrJob();
        job.setReportId(reportId);
        job.setProvider(ocrService.provider());
        job.setConsentRecordId(consentId);
        job.setConsentDeclaredAt(now);
        job.setCreatedBy(accountId);
        job.setStatus(OcrJobStatus.PENDING);
        job.setAttemptCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        try {
            ocrJobMapper.insert(job);
        } catch (DuplicateKeyException e) {
            // 并发提交：唯一约束保证同一报告只有一个活动任务
            OcrJob active = ocrJobMapper.findLatestByReport(reportId);
            if (active != null) {
                return OcrJobView.of(active);
            }
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT);
        }
        reportMapper.transitionStatus(reportId, SUBMITTABLE, ProcessingStatus.RECOGNIZING, now);

        long jobId = job.getId();
        auditService.success(accountId, AuditAction.REPORT_OCR_SUBMIT, AuditService.token("report", reportId));
        runAfterCommit(() -> processor.processAsync(jobId));
        return OcrJobView.of(job);
    }

    /** 拒绝第三方 OCR 或识别失败后，转为手动录入草稿。 */
    @Transactional
    public DraftReport startManualEntry(long accountId, long reportId) {
        access.requireOwnedReport(accountId, reportId);
        Report report = reportMapper.findByIdForUpdate(reportId);
        if (report.getProcessingStatus() != ProcessingStatus.PENDING_CONFIRMATION) {
            if (!SUBMITTABLE.contains(report.getProcessingStatus())) {
                throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT);
            }
            // 与识别一致：没有任何上传完成的照片时不允许进入录入，避免产生无原图可对照的空报告
            if (photoMapper.findCompleteByReport(reportId).isEmpty()) {
                throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT, NO_COMPLETE_PHOTO);
            }
            reportMapper.transitionStatus(reportId, SUBMITTABLE, ProcessingStatus.PENDING_CONFIRMATION, now());
            report.setProcessingStatus(ProcessingStatus.PENDING_CONFIRMATION);
        }
        return ReviewConfirmService.toDraftReport(report);
    }

    public OcrJobView getLatestJob(long accountId, long reportId) {
        access.requireOwnedReport(accountId, reportId);
        OcrJob job = ocrJobMapper.findLatestByReport(reportId);
        if (job == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return OcrJobView.of(job);
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
