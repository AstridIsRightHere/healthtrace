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
import com.healthtrace.report.ocr.OcrTextCipher;
import com.healthtrace.report.service.ReportViews.PhotoView;
import com.healthtrace.report.service.ReviewViews.ConfirmCommand;
import com.healthtrace.report.service.ReviewViews.ConfirmResult;
import com.healthtrace.report.service.ReviewViews.DraftReport;
import com.healthtrace.report.service.ReviewViews.DraftView;
import com.healthtrace.report.service.ReviewViews.ObservationInput;
import com.healthtrace.report.service.ReviewViews.ObservationView;
import com.healthtrace.report.service.ReviewViews.OcrJobView;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 草稿查看与整页确认（FR-007、FR-015）。
 * <ul>
 *   <li>确认前的观测值一律是 DRAFT，不会进入图表；确认使用报告 version 乐观锁。</li>
 *   <li>置信度只来自供应商；用户修改过的行置信度置空，新增行没有置信度，绝不伪造。</li>
 * </ul>
 * 行校验与规整（validate/isEdited/apply）同时供已确认报告的修改 {@link ReportRevisionService} 复用；
 * 提交识别与手动录入见 {@link OcrSubmissionService}。
 */
@Service
public class ReviewConfirmService {

    static final int NAME_MAX = 160;
    static final int VALUE_MAX = 160;
    static final int UNIT_MAX = 80;
    static final int REFERENCE_MAX = 160;
    static final int MAX_OBSERVATIONS = 200;

    private final ReportAccess access;
    private final ReportMapper reportMapper;
    private final PhotoMapper photoMapper;
    private final OcrJobMapper ocrJobMapper;
    private final ObservationMapper observationMapper;
    private final OcrTextCipher cipher;
    private final AuditService auditService;
    private final Clock clock;

    public ReviewConfirmService(ReportAccess access, ReportMapper reportMapper, PhotoMapper photoMapper,
                                OcrJobMapper ocrJobMapper, ObservationMapper observationMapper,
                                OcrTextCipher cipher, AuditService auditService, Clock clock) {
        this.access = access;
        this.reportMapper = reportMapper;
        this.photoMapper = photoMapper;
        this.ocrJobMapper = ocrJobMapper;
        this.observationMapper = observationMapper;
        this.cipher = cipher;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 草稿详情

    public DraftView getDraft(long accountId, long reportId) {
        Report report = access.requireOwnedReport(accountId, reportId);
        List<PhotoView> photos = photoMapper.findCompleteByReport(reportId).stream()
                .map(ReviewConfirmService::toPhotoView)
                .toList();
        OcrJob job = ocrJobMapper.findLatestByReport(reportId);
        String rawText = job != null && job.getStatus() == OcrJobStatus.SUCCEEDED
                ? cipher.decrypt(job.getRawTextCiphertext())
                : null;
        ObservationStatus wanted = report.getProcessingStatus() == ProcessingStatus.CONFIRMED
                ? ObservationStatus.FORMAL
                : ObservationStatus.DRAFT;
        List<ObservationView> observations = observationMapper.findByReport(reportId, wanted).stream()
                .map(ReviewConfirmService::toObservationView)
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
        auditService.success(accountId, AuditAction.REPORT_CONFIRM, AuditService.token("report", reportId));
        return new ConfirmResult(reportId, ProcessingStatus.CONFIRMED, command.version() + 1,
                command.examinationDate(), inputs.size());
    }

    // ---------------------------------------------------------------- helpers

    void validate(ConfirmCommand command) {
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
                validateInput(errors, "observations[" + i + "].", in);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
    }

    /** 单行结果的字段校验；prefix 为字段路径前缀（如 "observations[0]." 或 "operations[1].observation."）。 */
    static void validateInput(List<FieldErrorItem> errors, String prefix, ObservationInput in) {
        requireText(errors, prefix, "originalName", in.originalName(), NAME_MAX, "请填写项目名称");
        requireText(errors, prefix, "originalValue", in.originalValue(), VALUE_MAX, "请填写结果");
        limit(errors, prefix, "unitText", in.unitText(), UNIT_MAX);
        limit(errors, prefix, "referenceText", in.referenceText(), REFERENCE_MAX);
    }

    private static void requireText(List<FieldErrorItem> errors, String prefix, String name, String value, int max,
                                    String requiredMessage) {
        if (value == null || value.isBlank()) {
            errors.add(new FieldErrorItem(prefix + name, "REQUIRED", requiredMessage));
        } else {
            limit(errors, prefix, name, value, max);
        }
    }

    private static void limit(List<FieldErrorItem> errors, String prefix, String name, String value, int max) {
        if (value != null && value.trim().codePointCount(0, value.trim().length()) > max) {
            errors.add(new FieldErrorItem(prefix + name, "TOO_LONG", "不能超过 " + max + " 个字符"));
        }
    }

    private static FieldErrorItem field(int i, String name, String reason, String message) {
        return new FieldErrorItem("observations[" + i + "]." + name, reason, message);
    }

    static boolean isEdited(Observation o, ObservationInput in) {
        return !Objects.equals(o.getOriginalName(), trim(in.originalName()))
                || !Objects.equals(o.getOriginalValue(), trim(in.originalValue()))
                || !Objects.equals(o.getUnitText(), trimToNull(in.unitText()))
                || !Objects.equals(o.getReferenceText(), trimToNull(in.referenceText()));
    }

    static void apply(Observation o, ObservationInput in, int rowOrder, LocalDateTime now) {
        o.setOriginalName(trim(in.originalName()));
        o.setOriginalValue(trim(in.originalValue()));
        o.setUnitText(trimToNull(in.unitText()));
        o.setReferenceText(trimToNull(in.referenceText()));
        o.setSourcePhotoId(in.sourcePhotoId());
        o.setRowOrder(rowOrder);
        o.setUpdatedAt(now);
        ObservationParser.derive(o);
    }

    static String trim(String s) {
        return s == null ? null : s.trim();
    }

    static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    static DraftReport toDraftReport(Report r) {
        return new DraftReport(r.getId(), r.getProfileId(), r.getTitle(), r.getExaminationDate(),
                r.getProcessingStatus(), r.getVersion());
    }

    private static PhotoView toPhotoView(Photo p) {
        return new PhotoView(p.getId(), p.getReportId(), p.getPageOrder(), p.getMediaType(), p.getByteSize(),
                p.getPreviewObjectKey() != null);
    }

    static ObservationView toObservationView(Observation o) {
        return new ObservationView(o.getId(), o.getOriginalName(), o.getOriginalValue(), o.getUnitText(),
                o.getReferenceText(), o.getResultKind(), o.getAbnormalFlag(), o.getConfidence(),
                o.getSourcePhotoId(), o.getRowOrder());
    }
}
