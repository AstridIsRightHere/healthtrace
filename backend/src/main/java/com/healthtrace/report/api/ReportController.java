package com.healthtrace.report.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.report.domain.DuplicateDecision;
import com.healthtrace.report.domain.ReportListFilter;
import com.healthtrace.report.service.ReportService;
import com.healthtrace.report.service.ReportViews.PhotoContent;
import com.healthtrace.report.service.ReportViews.PhotoUploadResult;
import com.healthtrace.report.service.ReportViews.PhotoVariant;
import com.healthtrace.report.service.ReportViews.ReportListItem;
import com.healthtrace.report.service.ReportViews.ReportView;
import com.healthtrace.report.service.ReportViews;
import com.healthtrace.report.service.ReportViews.UploadOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * 报告草稿与图片上传（FR-004、FR-005）。所有端点需登录，写操作需 CSRF 令牌；不在白名单内。
 */
@RestController
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** 标题按码点上限在服务端校验，这里只做粗上限防止超大请求。 */
    public record CreateReportRequest(
            @Size(max = 1024) String title,
            LocalDate examinationDate) {
    }

    @PostMapping("/api/v1/patient-profiles/{profileId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportView createReport(@AuthenticationPrincipal AccountPrincipal principal,
                                   @PathVariable long profileId,
                                   @Valid @RequestBody(required = false) CreateReportRequest request) {
        CreateReportRequest body = request == null ? new CreateReportRequest(null, null) : request;
        return reportService.createReport(principal.accountId(), profileId, body.title(), body.examinationDate());
    }

    @GetMapping("/api/v1/patient-profiles/{profileId}/reports")
    public List<ReportListItem> listReports(@AuthenticationPrincipal AccountPrincipal principal,
                                            @PathVariable long profileId,
                                            @RequestParam(name = "status", defaultValue = "DRAFT") String status) {
        ReportListFilter filter = parseEnum(ReportListFilter.class, status, "status", "当前仅支持 status=DRAFT 或 CONFIRMED");
        return reportService.listReports(principal.accountId(), profileId, filter);
    }

    /** 已确认报告按种类聚合（FR-010 报告对比入口）。他人档案 404 HT-3001。 */
    @GetMapping("/api/v1/patient-profiles/{profileId}/report-groups")
    public List<ReportViews.ReportGroup> listReportGroups(@AuthenticationPrincipal AccountPrincipal principal,
                                                          @PathVariable long profileId) {
        return reportService.listConfirmedGroups(principal.accountId(), profileId);
    }

    /**
     * 一次上传一个文件。保存成功 201；疑似重复且未选择 KEEP 时不保存，返回 200 + DUPLICATE_SUSPECTED。
     */
    @PostMapping(path = "/api/v1/reports/{reportId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PhotoUploadResult> uploadPhoto(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable long reportId,
            @RequestPart("original") MultipartFile original,
            @RequestPart(name = "preview", required = false) MultipartFile preview,
            @RequestParam("pageOrder") Integer pageOrder,
            @RequestParam(name = "duplicateDecision", defaultValue = "ASK") String duplicateDecision) {
        DuplicateDecision decision = parseEnum(DuplicateDecision.class, duplicateDecision, "duplicateDecision",
                "duplicateDecision 只能是 ASK 或 KEEP");
        PhotoUploadResult result = reportService.uploadPhoto(principal.accountId(), reportId, original, preview,
                pageOrder, decision);
        HttpStatus status = result.result() == UploadOutcome.UPLOADED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    /**
     * 读取图片。对象存储返回 302 到 5 分钟签名地址；本地存储由应用回传，禁止缓存。
     */
    @GetMapping("/api/v1/photos/{photoId}/access")
    public ResponseEntity<InputStreamResource> accessPhoto(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable long photoId,
            @RequestParam(name = "variant", defaultValue = "preview") String variant) {
        PhotoVariant photoVariant = parseEnum(PhotoVariant.class, variant, "variant",
                "variant 只能是 preview 或 original");
        PhotoContent content = reportService.openPhoto(principal.accountId(), photoId, photoVariant);
        CacheControl noStore = CacheControl.noStore().cachePrivate();
        if (content.redirect() != null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(content.redirect())
                    .cacheControl(noStore)
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(noStore)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .contentLength(content.byteSize())
                .body(new InputStreamResource(content.content()));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field, String message) {
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message,
                    List.of(new FieldErrorItem(field, "Invalid", message)));
        }
    }
}
