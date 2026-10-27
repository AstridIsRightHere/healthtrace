package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.report.domain.Observation;
import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.ReportRevision;
import com.healthtrace.report.mapper.ObservationMapper;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.mapper.ReportRevisionMapper;
import com.healthtrace.report.ocr.OcrTextCipher;
import com.healthtrace.report.service.ReviewViews.ConfirmCommand;
import com.healthtrace.report.service.ReviewViews.ObservationInput;
import com.healthtrace.report.service.RevisionViews.ChangeType;
import com.healthtrace.report.service.RevisionViews.DateChange;
import com.healthtrace.report.service.RevisionViews.ObservationSnapshot;
import com.healthtrace.report.service.RevisionViews.ReviseCommand;
import com.healthtrace.report.service.RevisionViews.ReviseResult;
import com.healthtrace.report.service.RevisionViews.RevisionChange;
import com.healthtrace.report.service.RevisionViews.RevisionPayload;
import com.healthtrace.report.service.RevisionViews.RevisionView;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 修改已确认报告（FR-014）。
 * <ul>
 *   <li>原图与 OCR 原文不被改写；正式 observation 按差异更新 / 新增 / 删除，旧值只保存在 report_revision。</li>
 *   <li>每次修改生成一条修改记录（from_version → to_version），差异 JSON 经字段加密后存储。</li>
 *   <li>报告 version 乐观锁；不存在、无权访问统一 404；未确认的报告不能走此接口（409）。</li>
 * </ul>
 */
@Service
public class ReportRevisionService {

    public static final int REASON_MAX = 240;

    private final ReportAccess access;
    private final ReportMapper reportMapper;
    private final PhotoMapper photoMapper;
    private final ObservationMapper observationMapper;
    private final ReportRevisionMapper revisionMapper;
    private final ReviewService reviewService;
    private final OcrTextCipher cipher;
    private final JsonMapper jsonMapper;
    private final AuditService auditService;
    private final Clock clock;

    public ReportRevisionService(ReportAccess access, ReportMapper reportMapper, PhotoMapper photoMapper,
                                 ObservationMapper observationMapper, ReportRevisionMapper revisionMapper,
                                 ReviewService reviewService, OcrTextCipher cipher, JsonMapper jsonMapper,
                                 AuditService auditService, Clock clock) {
        this.access = access;
        this.reportMapper = reportMapper;
        this.photoMapper = photoMapper;
        this.observationMapper = observationMapper;
        this.revisionMapper = revisionMapper;
        this.reviewService = reviewService;
        this.cipher = cipher;
        this.jsonMapper = jsonMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public ReviseResult revise(long accountId, long reportId, ReviseCommand command) {
        access.requireOwnedReport(accountId, reportId);
        if (command == null) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST);
        }
        // 复用确认时的字段校验（版本号、日期、条数、长度）
        reviewService.validate(new ConfirmCommand(command.version(), command.examinationDate(),
                command.observations()));
        String reason = ReviewService.trimToNull(command.reason());
        if (reason != null && reason.codePointCount(0, reason.length()) > REASON_MAX) {
            throw validation("reason", "TOO_LONG", "修改原因最多 " + REASON_MAX + " 字");
        }

        Report report = reportMapper.findByIdForUpdate(reportId);
        if (report == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (report.getProcessingStatus() != ProcessingStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT, "只有已确认的报告可以修改");
        }
        if (report.getVersion() != command.version()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }

        Map<Long, Observation> formal = new LinkedHashMap<>();
        for (Observation o : observationMapper.findByReport(reportId, ObservationStatus.FORMAL)) {
            formal.put(o.getId(), o);
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
            if (in.id() != null && (!formal.containsKey(in.id()) || !seen.add(in.id()))) {
                errors.add(new FieldErrorItem("observations[" + i + "].id", "UNKNOWN", "该行已失效，请刷新后重试"));
            }
            if (in.sourcePhotoId() != null && !photoIds.contains(in.sourcePhotoId())) {
                errors.add(new FieldErrorItem("observations[" + i + "].sourcePhotoId", "UNKNOWN",
                        "来源照片不属于该报告"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }

        // ---- 计算差异
        List<RevisionChange> changes = new ArrayList<>();
        for (Observation o : formal.values()) {
            if (!seen.contains(o.getId())) {
                changes.add(new RevisionChange(ChangeType.REMOVED, snapshot(o), null));
            }
        }
        for (ObservationInput in : inputs) {
            if (in.id() == null) {
                changes.add(new RevisionChange(ChangeType.ADDED, null, snapshot(in)));
            } else {
                Observation o = formal.get(in.id());
                if (ReviewService.isEdited(o, in)) {
                    changes.add(new RevisionChange(ChangeType.UPDATED, snapshot(o), snapshot(in)));
                }
            }
        }
        LocalDate newDate = command.examinationDate();
        DateChange dateChange = Objects.equals(report.getExaminationDate(), newDate)
                ? null
                : new DateChange(text(report.getExaminationDate()), text(newDate));
        if (changes.isEmpty() && dateChange == null) {
            throw validation("observations", "NO_CHANGE", "没有检测到修改内容");
        }

        // ---- 写入正式数据
        LocalDateTime now = now();
        for (Long id : formal.keySet()) {
            if (!seen.contains(id)) {
                observationMapper.deleteFormalById(reportId, id);
            }
        }
        for (int i = 0; i < inputs.size(); i++) {
            ObservationInput in = inputs.get(i);
            if (in.id() != null) {
                Observation o = formal.get(in.id());
                boolean edited = ReviewService.isEdited(o, in);
                ReviewService.apply(o, in, i, now);
                if (edited) {
                    o.setConfidence(null);
                }
                o.setExaminationDate(newDate);
                observationMapper.updateFormal(o);
            } else {
                Observation o = new Observation();
                o.setReportId(reportId);
                o.setStatus(ObservationStatus.FORMAL);
                o.setCreatedAt(now);
                ReviewService.apply(o, in, i, now);
                o.setConfidence(null);
                o.setExaminationDate(newDate);
                o.setConfirmedAt(now);
                observationMapper.insert(o);
            }
        }
        // 名称或单位可能变化：updateFormal 已清空映射，这里按字典重新写入标准指标
        observationMapper.assignMetricDefinitions(reportId);

        long fromVersion = report.getVersion();
        if (reportMapper.revise(reportId, fromVersion, newDate, now) != 1) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }

        ReportRevision revision = new ReportRevision();
        revision.setReportId(reportId);
        revision.setActorAccountId(accountId);
        revision.setFromVersion(fromVersion);
        revision.setToVersion(fromVersion + 1);
        revision.setChangePayload(cipher.encrypt(
                jsonMapper.writeValueAsString(new RevisionPayload(dateChange, changes))));
        revision.setReason(reason);
        revision.setCreatedAt(now);
        try {
            revisionMapper.insert(revision);
        } catch (DuplicateKeyException e) {
            // uk_revision_report_version 兜底并发修改
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        auditService.success(accountId, AuditAction.REPORT_REVISE, AuditService.token("report", reportId));
        return new ReviseResult(reportId, fromVersion + 1, newDate, revision.getId(), changes.size());
    }

    /** 修改记录，最新在前。 */
    public List<RevisionView> listRevisions(long accountId, long reportId) {
        access.requireOwnedReport(accountId, reportId);
        return revisionMapper.findByReport(reportId).stream()
                .map(this::toView)
                .toList();
    }

    private RevisionView toView(ReportRevision r) {
        RevisionPayload payload = jsonMapper.readValue(cipher.decrypt(r.getChangePayload()), RevisionPayload.class);
        List<RevisionChange> changes = payload.changes() == null ? List.of() : payload.changes();
        return new RevisionView(r.getId(), r.getFromVersion(), r.getToVersion(), r.getReason(), r.getCreatedAt(),
                payload.examinationDate(), changes);
    }

    private static ObservationSnapshot snapshot(Observation o) {
        return new ObservationSnapshot(o.getOriginalName(), o.getOriginalValue(), o.getUnitText(),
                o.getReferenceText());
    }

    private static ObservationSnapshot snapshot(ObservationInput in) {
        return new ObservationSnapshot(ReviewService.trim(in.originalName()), ReviewService.trim(in.originalValue()),
                ReviewService.trimToNull(in.unitText()), ReviewService.trimToNull(in.referenceText()));
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static ApiException validation(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, List.of(new FieldErrorItem(field, reason, message)));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
