package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.report.domain.Observation;
import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.domain.OcrJob;
import com.healthtrace.report.domain.OcrJobStatus;
import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.mapper.ObservationMapper;
import com.healthtrace.report.mapper.OcrJobMapper;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.ocr.OcrService;
import com.healthtrace.report.ocr.OcrTextCipher;
import com.healthtrace.report.service.ReportViews.PhotoView;
import com.healthtrace.report.service.ReviewViews.ConfirmCommand;
import com.healthtrace.report.service.ReviewViews.ConfirmResult;
import com.healthtrace.report.service.ReviewViews.DraftReport;
import com.healthtrace.report.service.ReviewViews.DraftView;
import com.healthtrace.report.service.ReviewViews.ObservationInput;
import com.healthtrace.report.service.ReviewViews.ObservationView;
import com.healthtrace.report.service.ReviewViews.OcrJobView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * OCR 提交、草稿查看与整页确认（FR-006、FR-007、FR-014、FR-015）。
 * <ul>
 *   <li>提交识别必须显式同意第三方 OCR（NFR-002），否则 403 HT-4003，不会调用供应商。</li>
 *   <li>确认前的观测值一律是 DRAFT，不会进入图表；确认使用报告 version 乐观锁。</li>
 *   <li>置信度只来自供应商；用户修改过的行置信度置空，新增行没有置信度，绝不伪造。</li>
 * </ul>
 */
@Service
public class ReviewService {

    static final int NAME_MAX = 160;
    static final int VALUE_MAX = 160;
    static final int UNIT_MAX = 80;
    static final int REFERENCE_MAX = 160;
    static final int MAX_OBSERVATIONS = 200;
    static final String NO_COMPLETE_PHOTO = "报告还没有上传完成的照片";

    private static final Set<ProcessingStatus> SUBMITTABLE =
            EnumSet.of(ProcessingStatus.UPLOADING, ProcessingStatus.FAILED);

    private final ReportAccess access;
    private final ReportMapper reportMapper;
    private final PhotoMapper photoMapper;
    private final OcrJobMapper ocrJobMapper;
    private final ObservationMapper observationMapper;
    private final OcrService ocrService;
    private final OcrTextCipher cipher;
    private final OcrJobProcessor processor;
    private final Clock clock;

    public ReviewService(ReportAccess access, ReportMapper reportMapper, PhotoMapper photoMapper,
                         OcrJobMapper ocrJobMapper, ObservationMapper observationMapper, OcrService ocrService,
                         OcrTextCipher cipher, OcrJobProcessor processor, Clock clock) {
        this.access = access;
        this.reportMapper = reportMapper;
        this.photoMapper = photoMapper;
        this.ocrJobMapper = ocrJobMapper;
        this.observationMapper = observationMapper;
        this.ocrService = ocrService;
        this.cipher = cipher;
        this.processor = processor;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 提交识别

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
        OcrJob job = new OcrJob();
        job.setReportId(reportId);
        job.setProvider(ocrService.provider());
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
        return toDraftReport(report);
    }

    public OcrJobView getLatestJob(long accountId, long reportId) {
        access.requireOwnedReport(accountId, reportId);
        OcrJob job = ocrJobMapper.findLatestByReport(reportId);
        if (job == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return OcrJobView.of(job);
    }

    // ---------------------------------------------------------------- 草稿详情

    public DraftView getDraft(long accountId, long reportId) {
        Report report = access.requireOwnedReport(accountId, reportId);
        List<PhotoView> photos = photoMapper.findCompleteByReport(reportId).stream()
                .map(ReviewService::toPhotoView)
                .toList();
        OcrJob job = ocrJobMapper.findLatestByReport(reportId);
        String rawText = job != null && job.getStatus() == OcrJobStatus.SUCCEEDED
                ? cipher.decrypt(job.getRawTextCiphertext())
                : null;
        ObservationStatus wanted = report.getProcessingStatus() == ProcessingStatus.CONFIRMED
                ? ObservationStatus.FORMAL
                : ObservationStatus.DRAFT;
        List<ObservationView> observations = observationMapper.findByReport(reportId, wanted).stream()
                .map(ReviewService::toObservationView)
                .toList();
        return new DraftView(toDraftReport(report), photos, job == null ? null : OcrJobView.of(job), rawText,
                observations);
    }

    // ---------------------------------------------------------------- 整页确认

    @Transactional
    public ConfirmResult confirm(long accountId, long reportId, ConfirmCommand command) {
        access.requireOwnedReport(accountId, reportId);
        validate(command);

        Report report = reportMapper.findByIdForUpdate(reportId);
        if (report.getProcessingStatus() != ProcessingStatus.PENDING_CONFIRMATION) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT);
        }
        if (report.getVersion() != command.version()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }

        Map<Long, Observation> drafts = new HashMap<>();
        for (Observation o : observationMapper.findByReport(reportId, ObservationStatus.DRAFT)) {
            drafts.put(o.getId(), o);
        }
        Set<Long> photoIds = new HashSet<>();
        for (Photo p : photoMapper.findCompleteByReport(reportId)) {
            photoIds.add(p.getId());
        }

        List<FieldErrorItem> errors = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<ObservationInput> inputs = command.observations();
        for (int i = 0; i < inputs.size(); i++) {
            ObservationInput in = inputs.get(i);
            if (in.id() != null && (!drafts.containsKey(in.id()) || !seen.add(in.id()))) {
                errors.add(field(i, "id", "UNKNOWN", "该行已失效，请刷新后重试"));
            }
            if (in.sourcePhotoId() != null && !photoIds.contains(in.sourcePhotoId())) {
                errors.add(field(i, "sourcePhotoId", "UNKNOWN", "来源照片不属于该报告"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }

        LocalDateTime now = now();
        for (Long id : drafts.keySet()) {
            if (!seen.contains(id)) {
                observationMapper.deleteDraftById(reportId, id);
            }
        }
        for (int i = 0; i < inputs.size(); i++) {
            ObservationInput in = inputs.get(i);
            if (in.id() != null) {
                Observation o = drafts.get(in.id());
                boolean edited = isEdited(o, in);
                apply(o, in, i, now);
                if (edited) {
                    o.setConfidence(null);
                }
                observationMapper.updateDraft(o);
            } else {
                Observation o = new Observation();
                o.setReportId(reportId);
                o.setStatus(ObservationStatus.DRAFT);
                o.setCreatedAt(now);
                apply(o, in, i, now);
                o.setConfidence(null);
                observationMapper.insert(o);
            }
        }

        observationMapper.confirmDrafts(reportId, command.examinationDate(), now);
        // 按 V5 字典别名为正式结果写入标准指标；未命中的保持为空，不进入趋势（FR-008/010）
        observationMapper.assignMetricDefinitions(reportId);
        if (reportMapper.confirm(reportId, command.version(), command.examinationDate(), accountId, now) != 1) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        return new ConfirmResult(reportId, ProcessingStatus.CONFIRMED, command.version() + 1,
                command.examinationDate(), inputs.size());
    }

    // ---------------------------------------------------------------- helpers

    private void validate(ConfirmCommand command) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (command == null) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST);
        }
        if (command.version() == null) {
            errors.add(new FieldErrorItem("version", "REQUIRED", "缺少版本号，请刷新后重试"));
        }
        LocalDate date = command.examinationDate();
        if (date == null) {
            errors.add(new FieldErrorItem("examinationDate", "REQUIRED", "请填写检查日期"));
        } else if (date.isAfter(LocalDate.now(clock).plusDays(1))) {
            // 允许 +1 天以容忍用户所在时区早于 UTC
            errors.add(new FieldErrorItem("examinationDate", "FUTURE", "检查日期不能晚于今天"));
        }
        List<ObservationInput> inputs = command.observations();
        if (inputs == null || inputs.isEmpty()) {
            errors.add(new FieldErrorItem("observations", "REQUIRED", "至少需要一项检验结果"));
        } else if (inputs.size() > MAX_OBSERVATIONS) {
            errors.add(new FieldErrorItem("observations", "TOO_MANY", "单份报告最多 " + MAX_OBSERVATIONS + " 项"));
        } else {
            for (int i = 0; i < inputs.size(); i++) {
                ObservationInput in = inputs.get(i);
                if (in == null) {
                    errors.add(new FieldErrorItem("observations[" + i + "]", "REQUIRED", "该行为空"));
                    continue;
                }
                requireText(errors, i, "originalName", in.originalName(), NAME_MAX, "请填写项目名称");
                requireText(errors, i, "originalValue", in.originalValue(), VALUE_MAX, "请填写结果");
                limit(errors, i, "unitText", in.unitText(), UNIT_MAX);
                limit(errors, i, "referenceText", in.referenceText(), REFERENCE_MAX);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
    }

    private static void requireText(List<FieldErrorItem> errors, int i, String name, String value, int max,
                                    String requiredMessage) {
        if (value == null || value.isBlank()) {
            errors.add(field(i, name, "REQUIRED", requiredMessage));
        } else {
            limit(errors, i, name, value, max);
        }
    }

    private static void limit(List<FieldErrorItem> errors, int i, String name, String value, int max) {
        if (value != null && value.trim().codePointCount(0, value.trim().length()) > max) {
            errors.add(field(i, name, "TOO_LONG", "不能超过 " + max + " 个字符"));
        }
    }

    private static FieldErrorItem field(int i, String name, String reason, String message) {
        return new FieldErrorItem("observations[" + i + "]." + name, reason, message);
    }

    private static boolean isEdited(Observation o, ObservationInput in) {
        return !Objects.equals(o.getOriginalName(), trim(in.originalName()))
                || !Objects.equals(o.getOriginalValue(), trim(in.originalValue()))
                || !Objects.equals(o.getUnitText(), trimToNull(in.unitText()))
                || !Objects.equals(o.getReferenceText(), trimToNull(in.referenceText()));
    }

    private static void apply(Observation o, ObservationInput in, int rowOrder, LocalDateTime now) {
        o.setOriginalName(trim(in.originalName()));
        o.setOriginalValue(trim(in.originalValue()));
        o.setUnitText(trimToNull(in.unitText()));
        o.setReferenceText(trimToNull(in.referenceText()));
        o.setSourcePhotoId(in.sourcePhotoId());
        o.setRowOrder(rowOrder);
        o.setUpdatedAt(now);
        ObservationParser.derive(o);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
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

    private static DraftReport toDraftReport(Report r) {
        return new DraftReport(r.getId(), r.getProfileId(), r.getTitle(), r.getExaminationDate(),
                r.getProcessingStatus(), r.getVersion());
    }

    private static PhotoView toPhotoView(Photo p) {
        return new PhotoView(p.getId(), p.getReportId(), p.getPageOrder(), p.getMediaType(), p.getByteSize(),
                p.getPreviewObjectKey() != null);
    }

    private static ObservationView toObservationView(Observation o) {
        return new ObservationView(o.getId(), o.getOriginalName(), o.getOriginalValue(), o.getUnitText(),
                o.getReferenceText(), o.getResultKind(), o.getAbnormalFlag(), o.getConfidence(),
                o.getSourcePhotoId(), o.getRowOrder());
    }
}
