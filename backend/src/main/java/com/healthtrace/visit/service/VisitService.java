package com.healthtrace.visit.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.common.web.ListPage;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import com.healthtrace.visit.domain.LinkedReport;
import com.healthtrace.visit.domain.Visit;
import com.healthtrace.visit.mapper.VisitMapper;
import com.healthtrace.visit.service.VisitViews.LinkedReportView;
import com.healthtrace.visit.service.VisitViews.ReportVisitView;
import com.healthtrace.visit.service.VisitViews.TimelineEntryView;
import com.healthtrace.visit.service.VisitViews.VisitInput;
import com.healthtrace.visit.service.VisitViews.VisitView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 就诊时间线（FR-013）。
 * <p>
 * 访问控制：visitId / reportId 先解析到 profileId，再要求当前账号持有该档案的 ACTIVE 授权；
 * 不存在、无权访问、跨档案关联统一 404 HT-3001，不泄露其他档案的资源是否存在。
 * 就诊次数按就诊记录计数，关联报告不增加次数；删除就诊只删除关联，不删除报告。
 */
@Service
public class VisitService {

    public static final int HOSPITAL_MAX_LENGTH = 160;
    public static final int DEPARTMENT_MAX_LENGTH = 80;
    public static final int NOTE_MAX_LENGTH = 2000;

    private final VisitMapper visitMapper;
    private final ReportMapper reportMapper;
    private final ProfileMapper profileMapper;
    private final AuditService auditService;
    private final Clock clock;

    public VisitService(VisitMapper visitMapper, ReportMapper reportMapper, ProfileMapper profileMapper,
                        AuditService auditService, Clock clock) {
        this.visitMapper = visitMapper;
        this.reportMapper = reportMapper;
        this.profileMapper = profileMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * 就诊列表：就诊日倒序，附带关联报告。limit 为空表示全部；
     * total 为该档案就诊总数（SQL LIMIT + 独立 COUNT）。
     */
    public ListPage<VisitView> list(long accountId, long profileId, Integer limit) {
        requireProfileAccess(accountId, profileId);
        List<Visit> visits = visitMapper.findByProfile(profileId, limit);
        long total = limit == null || visits.size() < limit ? visits.size() : visitMapper.countByProfile(profileId);
        return new ListPage<>(toViews(visits), total);
    }

    public VisitView get(long accountId, long visitId) {
        return toViews(List.of(requireOwnedVisit(accountId, visitId))).getFirst();
    }

    /**
     * 合并时间线（任务三）：就诊记录与「未关联任何就诊的报告」按日期倒序排在同一条轴上，
     * 让「某年某月某日去了某医院、做了什么检查、拿到什么报告」一屏可读。
     * 已关联就诊的报告不单独成条，只挂在该就诊下，避免同一份报告出现两次。
     * 日期为空的报告排在最后；limit 作用于合并后的条目数。
     */
    public List<TimelineEntryView> timeline(long accountId, long profileId, Integer limit) {
        requireProfileAccess(accountId, profileId);
        Stream<TimelineEntryView> visits = toViews(visitMapper.findByProfile(profileId, null)).stream()
                .map(TimelineEntryView::ofVisit);
        Stream<TimelineEntryView> reports = visitMapper.findUnlinkedReports(profileId, null).stream()
                .map(VisitService::toView)
                .map(TimelineEntryView::ofReport);
        Stream<TimelineEntryView> merged = Stream.concat(visits, reports)
                .sorted(Comparator.comparing(TimelineEntryView::date,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return (limit == null ? merged : merged.limit(limit)).toList();
    }

    @Transactional
    public VisitView create(long accountId, long profileId, VisitInput input) {
        requireProfileAccess(accountId, profileId);
        Visit visit = new Visit();
        apply(visit, input);
        LocalDateTime now = now();
        visit.setProfileId(profileId);
        visit.setCreatedBy(accountId);
        visit.setVersion(0L);
        visit.setCreatedAt(now);
        visit.setUpdatedAt(now);
        visitMapper.insert(visit);
        auditService.success(accountId, AuditAction.VISIT_CREATE, AuditService.token("visit", visit.getId()));
        return toViews(List.of(visit)).getFirst();
    }

    @Transactional
    public VisitView update(long accountId, long visitId, long expectedVersion, VisitInput input) {
        Visit visit = requireOwnedVisit(accountId, visitId);
        apply(visit, input);
        visit.setUpdatedAt(now());
        if (visitMapper.update(visit, expectedVersion) == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        auditService.success(accountId, AuditAction.VISIT_UPDATE, AuditService.token("visit", visitId));
        return get(accountId, visitId);
    }

    @Transactional
    public void delete(long accountId, long visitId) {
        requireOwnedVisit(accountId, visitId);
        visitMapper.delete(visitId);
        auditService.success(accountId, AuditAction.VISIT_DELETE, AuditService.token("visit", visitId));
    }

    /** 关联同档案报告（幂等）；报告不存在、不可见或属于其他档案均 404。 */
    @Transactional
    public VisitView linkReport(long accountId, long visitId, long reportId) {
        Visit visit = requireOwnedVisit(accountId, visitId);
        requireSameProfileReport(visit, reportId);
        visitMapper.link(visitId, reportId, now());
        auditService.success(accountId, AuditAction.VISIT_LINK_REPORT, AuditService.token("visit", visitId));
        return get(accountId, visitId);
    }

    /** 解除关联（幂等）；只删除关联行，报告本身保留。 */
    @Transactional
    public VisitView unlinkReport(long accountId, long visitId, long reportId) {
        Visit visit = requireOwnedVisit(accountId, visitId);
        requireSameProfileReport(visit, reportId);
        visitMapper.unlink(visitId, reportId);
        auditService.success(accountId, AuditAction.VISIT_UNLINK_REPORT, AuditService.token("visit", visitId));
        return get(accountId, visitId);
    }

    /**
     * 报告详情反查其关联的所有就诊（任务三）。报告不存在、已彻底删除或不属于当前账号的档案统一 404。
     * 只读，不写审计（与报告详情本身的读取一致）。
     */
    public List<ReportVisitView> listVisitsByReport(long accountId, long reportId) {
        Report report = reportMapper.findById(reportId);
        if (report == null) {
            throw notFound();
        }
        requireProfileAccess(accountId, report.getProfileId());
        return visitMapper.findVisitsByReport(reportId).stream()
                .map(v -> new ReportVisitView(v.getId(), v.getVisitDate(), v.getHospitalText(), v.getDepartment(),
                        v.getNote()))
                .toList();
    }

    // ---------------------------------------------------------------- helpers

    private void requireProfileAccess(long accountId, long profileId) {
        if (profileMapper.findActiveByAccountAndProfile(accountId, profileId) == null) {
            throw notFound();
        }
    }

    private Visit requireOwnedVisit(long accountId, long visitId) {
        Visit visit = visitMapper.findById(visitId);
        if (visit == null) {
            throw notFound();
        }
        requireProfileAccess(accountId, visit.getProfileId());
        return visit;
    }

    private void requireSameProfileReport(Visit visit, long reportId) {
        Report report = reportMapper.findById(reportId);
        if (report == null || report.getVisibilityStatus() != VisibilityStatus.NORMAL
                || !report.getProfileId().equals(visit.getProfileId())) {
            throw notFound();
        }
    }

    private void apply(Visit visit, VisitInput input) {
        if (input == null || input.visitDate() == null) {
            throw validation("visitDate", "NotNull", "请填写就诊日期");
        }
        // 以全球最早时区为界，避免用户本地“今天”被误判为未来
        if (input.visitDate().isAfter(LocalDate.now(clock.withZone(ZoneOffset.ofHours(14))))) {
            throw validation("visitDate", "PastOrPresent", "就诊日期不能晚于今天");
        }
        String hospital = clean(input.hospitalText());
        if (hospital == null) {
            throw validation("hospitalText", "NotBlank", "请填写医院");
        }
        visit.setVisitDate(input.visitDate());
        visit.setHospitalText(limit("hospitalText", hospital, HOSPITAL_MAX_LENGTH, "医院"));
        visit.setDepartment(limit("department", clean(input.department()), DEPARTMENT_MAX_LENGTH, "科室"));
        visit.setNote(limit("note", clean(input.note()), NOTE_MAX_LENGTH, "备注"));
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private static String limit(String field, String value, int max, String label) {
        if (value != null && value.codePointCount(0, value.length()) > max) {
            throw validation(field, "Size", label + "不能超过 " + max + " 个字符");
        }
        return value;
    }

    private List<VisitView> toViews(List<Visit> visits) {
        if (visits.isEmpty()) {
            return List.of();
        }
        Map<Long, List<LinkedReportView>> reports = visitMapper
                .findLinkedReports(visits.stream().map(Visit::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(LinkedReport::getVisitId,
                        Collectors.mapping(VisitService::toView, Collectors.toList())));
        return visits.stream()
                .map(v -> new VisitView(v.getId(), v.getProfileId(), v.getVisitDate(), v.getHospitalText(),
                        v.getDepartment(), v.getNote(), v.getVersion(), v.getCreatedAt(), v.getUpdatedAt(),
                        reports.getOrDefault(v.getId(), List.of())))
                .toList();
    }

    private static LinkedReportView toView(LinkedReport r) {
        return new LinkedReportView(r.getReportId(), r.getTitle(), r.getReportType(), r.getReportKind(),
                r.getExaminationDate(), r.getConclusionText(), r.getProcessingStatus());
    }

    private LocalDateTime now() {
        // DATETIME 列只保存到秒：截断后响应与库内值一致
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static ApiException validation(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldErrorItem(field, reason, message)));
    }
}
