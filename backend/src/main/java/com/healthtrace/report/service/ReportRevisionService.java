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
import com.healthtrace.report.service.ReviewViews.ObservationInput;
import com.healthtrace.report.service.RevisionViews.ChangeType;
import com.healthtrace.report.service.RevisionViews.CreateRevisionCommand;
import com.healthtrace.report.service.RevisionViews.DateChange;
import com.healthtrace.report.service.RevisionViews.ObservationSnapshot;
import com.healthtrace.report.service.RevisionViews.ObservationVersionView;
import com.healthtrace.report.service.RevisionViews.OperationType;
import com.healthtrace.report.service.RevisionViews.ReviseResult;
import com.healthtrace.report.service.RevisionViews.RevisionChange;
import com.healthtrace.report.service.RevisionViews.RevisionOperation;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 修改已确认报告（FR-014）：按行新增 / 修改 / 删除正式结果，保留医疗审计链。
 * <ul>
 *   <li>原图与 OCR 原文不被改写；正式结果行不被物理覆盖或删除：
 *       修改 = 旧行标记 SUPERSEDED + 插入新 FORMAL 行（supersedes_id 指向旧行）；删除 = 旧行标记 RETRACTED。</li>
 *   <li>每次修改生成一条修改记录（from_version → to_version），差异 JSON（含新旧行 id）经字段加密后存储；
 *       新行 created_revision_id、失效行 retired_revision_id 指向该记录。</li>
 *   <li>报告 version 乐观锁；不存在、无权访问统一 404；未确认的报告不能走此接口（409）。</li>
 * </ul>
 */
@Service
public class ReportRevisionService {

    public static final int REASON_MAX = 240;

    private static final Set<ObservationStatus> HISTORY_STATUSES =
            EnumSet.of(ObservationStatus.FORMAL, ObservationStatus.SUPERSEDED, ObservationStatus.RETRACTED);

    private final ReportAccess access;
    private final ReportMapper reportMapper;
    private final PhotoMapper photoMapper;
    private final ObservationMapper observationMapper;
    private final ReportRevisionMapper revisionMapper;
    private final OcrTextCipher cipher;
    private final JsonMapper jsonMapper;
    private final AuditService auditService;
    private final Clock clock;

    public ReportRevisionService(ReportAccess access, ReportMapper reportMapper, PhotoMapper photoMapper,
                                 ObservationMapper observationMapper, ReportRevisionMapper revisionMapper,
                                 OcrTextCipher cipher, JsonMapper jsonMapper, AuditService auditService, Clock clock) {
        this.access = access;
        this.reportMapper = reportMapper;
        this.photoMapper = photoMapper;
        this.observationMapper = observationMapper;
        this.revisionMapper = revisionMapper;
        this.cipher = cipher;
        this.jsonMapper = jsonMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public ReviseResult create(long accountId, long reportId, CreateRevisionCommand command) {
        access.requireOwnedReport(accountId, reportId);
        if (command == null) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST);
        }
        List<RevisionOperation> ops = command.operations() == null ? List.of() : command.operations();
        validateShape(command, ops);
        String reason = ReviewConfirmService.trimToNull(command.reason());

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

        // ---- 与当前数据相关的校验
        List<FieldErrorItem> errors = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int adds = 0;
        int deletes = 0;
        for (int i = 0; i < ops.size(); i++) {
            RevisionOperation op = ops.get(i);
            String prefix = "operations[" + i + "]";
            if (op.op() != OperationType.ADD) {
                Long id = op.observationId();
                if (!formal.containsKey(id)) {
                    errors.add(new FieldErrorItem(prefix + ".observationId", "UNKNOWN", "该结果已失效，请刷新后重试"));
                    continue;
                }
                if (!seen.add(id)) {
                    errors.add(new FieldErrorItem(prefix + ".observationId", "DUPLICATE", "同一结果只能出现一次"));
                    continue;
                }
            }
            ObservationInput in = op.observation();
            if (op.op() != OperationType.DELETE && in.sourcePhotoId() != null
                    && !photoIds.contains(in.sourcePhotoId())) {
                errors.add(new FieldErrorItem(prefix + ".observation.sourcePhotoId", "UNKNOWN", "来源照片不属于该报告"));
            }
            switch (op.op()) {
                case ADD -> adds++;
                case DELETE -> deletes++;
                case UPDATE -> {
                    if (!isChanged(formal.get(op.observationId()), in)) {
                        errors.add(new FieldErrorItem(prefix + ".observation", "NO_CHANGE", "该结果没有修改"));
                    }
                }
            }
        }
        if (errors.isEmpty() && formal.size() - deletes + adds <= 0) {
            errors.add(new FieldErrorItem("operations", "LAST_OBSERVATION", "已确认的报告至少保留一项检验结果"));
        }
        if (errors.isEmpty() && formal.size() - deletes + adds > ReviewConfirmService.MAX_OBSERVATIONS) {
            errors.add(new FieldErrorItem("operations", "TOO_MANY",
                    "单份报告最多 " + ReviewConfirmService.MAX_OBSERVATIONS + " 项"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }

        LocalDate oldDate = report.getExaminationDate();
        LocalDate newDate = command.examinationDate() == null ? oldDate : command.examinationDate();
        DateChange dateChange = Objects.equals(oldDate, newDate) ? null : new DateChange(text(oldDate), text(newDate));
        if (ops.isEmpty() && dateChange == null) {
            throw validation("operations", "NO_CHANGE", "没有检测到修改内容");
        }

        // ---- 报告版本（乐观锁）
        LocalDateTime now = now();
        long fromVersion = report.getVersion();
        if (reportMapper.revise(reportId, fromVersion, newDate, now) != 1) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }

        // ---- 插入新行（旧行此时仍为 FORMAL，随后在修改记录写入后失效）
        int nextRowOrder = observationMapper.maxRowOrder(reportId) + 1;
        List<RevisionChange> changes = new ArrayList<>();
        List<Long> newIds = new ArrayList<>();
        Map<Long, ObservationStatus> retire = new LinkedHashMap<>();
        for (RevisionOperation op : ops) {
            switch (op.op()) {
                case ADD -> {
                    Observation created = newRow(reportId, op.observation(), nextRowOrder++, null, null, newDate, now);
                    observationMapper.insert(created);
                    newIds.add(created.getId());
                    changes.add(new RevisionChange(ChangeType.ADDED, null, created.getId(), null, snapshot(created)));
                }
                case UPDATE -> {
                    Observation old = formal.get(op.observationId());
                    Observation created = newRow(reportId, op.observation(), old.getRowOrder(), old.getId(),
                            old.getSourcePhotoId(), newDate, now);
                    observationMapper.insert(created);
                    newIds.add(created.getId());
                    retire.put(old.getId(), ObservationStatus.SUPERSEDED);
                    changes.add(new RevisionChange(ChangeType.UPDATED, old.getId(), created.getId(), snapshot(old),
                            snapshot(created)));
                }
                case DELETE -> {
                    Observation old = formal.get(op.observationId());
                    retire.put(old.getId(), ObservationStatus.RETRACTED);
                    changes.add(new RevisionChange(ChangeType.REMOVED, old.getId(), null, snapshot(old), null));
                }
            }
        }

        // ---- 修改记录（只追加）
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
        long revisionId = revision.getId();

        // ---- 旧行失效、新行关联修改记录
        for (Map.Entry<Long, ObservationStatus> e : retire.entrySet()) {
            if (observationMapper.retireFormal(reportId, e.getKey(), e.getValue(), revisionId, now) != 1) {
                throw new ApiException(ErrorCode.VERSION_CONFLICT);
            }
        }
        if (!newIds.isEmpty()) {
            observationMapper.assignCreatedRevision(reportId, newIds, revisionId);
        }
        if (dateChange != null) {
            observationMapper.updateFormalExaminationDate(reportId, newDate, now);
        }
        // 新行未带标准指标映射，这里按字典写入
        observationMapper.assignMetricDefinitions(reportId);

        auditService.success(accountId, AuditAction.REPORT_REVISE, AuditService.token("report", reportId));
        return new ReviseResult(reportId, fromVersion + 1, newDate, revisionId,
                changes.size() + (dateChange == null ? 0 : 1));
    }

    /** 修改记录，最新在前。 */
    public List<RevisionView> listRevisions(long accountId, long reportId) {
        access.requireOwnedReport(accountId, reportId);
        return revisionMapper.findByReport(reportId).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 某一结果的版本链（经 supersedes_id 串联），最新在前；observationId 可为链上任一版本。
     * 草稿或不属于该报告的行统一 404。
     */
    public List<ObservationVersionView> observationHistory(long accountId, long reportId, long observationId) {
        access.requireOwnedReport(accountId, reportId);
        Map<Long, Observation> byId = new HashMap<>();
        Map<Long, Observation> successor = new HashMap<>();
        for (Observation o : observationMapper.findAllByReport(reportId)) {
            byId.put(o.getId(), o);
            if (o.getSupersedesId() != null) {
                successor.put(o.getSupersedesId(), o);
            }
        }
        Observation head = byId.get(observationId);
        if (head == null || !HISTORY_STATUSES.contains(head.getStatus())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Set<Long> guard = new HashSet<>();
        while (successor.containsKey(head.getId()) && guard.add(head.getId())) {
            head = successor.get(head.getId());
        }
        List<ObservationVersionView> chain = new ArrayList<>();
        guard.clear();
        for (Observation o = head; o != null && guard.add(o.getId());
             o = o.getSupersedesId() == null ? null : byId.get(o.getSupersedesId())) {
            chain.add(new ObservationVersionView(o.getId(), o.getSupersedesId(), o.getStatus(), o.getOriginalName(),
                    o.getOriginalValue(), o.getUnitText(), o.getReferenceText(), o.getExaminationDate(),
                    o.getSourcePhotoId(), o.getCreatedRevisionId(), o.getRetiredRevisionId(), o.getCreatedAt(),
                    o.getRetiredAt()));
        }
        return chain;
    }

    // ---------------------------------------------------------------- helpers

    /** 与数据无关的请求校验：版本号、日期、原因长度、操作结构与字段。 */
    private void validateShape(CreateRevisionCommand command, List<RevisionOperation> ops) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (command.version() == null) {
            errors.add(new FieldErrorItem("version", "REQUIRED", "缺少版本号，请刷新后重试"));
        }
        LocalDate date = command.examinationDate();
        if (date != null && date.isAfter(LocalDate.now(clock).plusDays(1))) {
            // 允许 +1 天以容忍用户所在时区早于 UTC
            errors.add(new FieldErrorItem("examinationDate", "FUTURE", "检查日期不能晚于今天"));
        }
        String reason = ReviewConfirmService.trimToNull(command.reason());
        if (reason != null && reason.codePointCount(0, reason.length()) > REASON_MAX) {
            errors.add(new FieldErrorItem("reason", "TOO_LONG", "修改原因最多 " + REASON_MAX + " 字"));
        }
        if (ops.size() > ReviewConfirmService.MAX_OBSERVATIONS) {
            errors.add(new FieldErrorItem("operations", "TOO_MANY",
                    "单次最多 " + ReviewConfirmService.MAX_OBSERVATIONS + " 项操作"));
        } else {
            for (int i = 0; i < ops.size(); i++) {
                RevisionOperation op = ops.get(i);
                String prefix = "operations[" + i + "]";
                if (op == null || op.op() == null) {
                    errors.add(new FieldErrorItem(prefix + ".op", "REQUIRED", "请指定操作类型"));
                    continue;
                }
                if (op.op() != OperationType.ADD && op.observationId() == null) {
                    errors.add(new FieldErrorItem(prefix + ".observationId", "REQUIRED", "请指定要修改的结果"));
                }
                if (op.op() != OperationType.DELETE) {
                    if (op.observation() == null) {
                        errors.add(new FieldErrorItem(prefix + ".observation", "REQUIRED", "缺少结果内容"));
                    } else {
                        ReviewConfirmService.validateInput(errors, prefix + ".observation.", op.observation());
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
    }

    /** UPDATE 是否真的有变化：可见字段或（显式给出的）来源照片。 */
    private static boolean isChanged(Observation old, ObservationInput in) {
        return ReviewConfirmService.isEdited(old, in)
                || (in.sourcePhotoId() != null && !in.sourcePhotoId().equals(old.getSourcePhotoId()));
    }

    private static Observation newRow(long reportId, ObservationInput in, int rowOrder, Long supersedesId,
                                      Long fallbackPhotoId, LocalDate examinationDate, LocalDateTime now) {
        Observation o = new Observation();
        o.setReportId(reportId);
        o.setStatus(ObservationStatus.FORMAL);
        o.setCreatedAt(now);
        ReviewConfirmService.apply(o, in, rowOrder, now);
        if (o.getSourcePhotoId() == null) {
            o.setSourcePhotoId(fallbackPhotoId);
        }
        o.setConfidence(null);
        o.setExaminationDate(examinationDate);
        o.setConfirmedAt(now);
        o.setSupersedesId(supersedesId);
        return o;
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
