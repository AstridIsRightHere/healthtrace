package com.healthtrace.visit.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.common.web.ListPage;
import com.healthtrace.visit.service.VisitService;
import com.healthtrace.visit.service.VisitViews.VisitInput;
import com.healthtrace.visit.service.VisitViews.VisitView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 就诊时间线（FR-013）。所有端点需登录，写操作需 CSRF 令牌；不存在、无权访问、跨档案关联统一 404 HT-3001。
 */
@RestController
public class VisitController {

    private final VisitService visitService;

    public VisitController(VisitService visitService) {
        this.visitService = visitService;
    }

    /** 字段长度按码点在服务端精确校验，这里只做粗上限防止超大请求。 */
    public record VisitRequest(
            LocalDate visitDate,
            @Size(max = 1024) String hospitalText,
            @Size(max = 1024) String department,
            @Size(max = 8192) String note,
            Long version) {

        VisitInput toInput() {
            return new VisitInput(visitDate, hospitalText, department, note);
        }
    }

    @GetMapping("/api/v1/patient-profiles/{profileId}/visits")
    public ListPage<VisitView> list(@AuthenticationPrincipal AccountPrincipal principal,
                                    @PathVariable long profileId,
                                    @RequestParam(required = false) String limit) {
        return visitService.list(principal.accountId(), profileId, ListPage.parseLimit(limit));
    }

    @PostMapping("/api/v1/patient-profiles/{profileId}/visits")
    @ResponseStatus(HttpStatus.CREATED)
    public VisitView create(@AuthenticationPrincipal AccountPrincipal principal,
                            @PathVariable long profileId,
                            @Valid @RequestBody VisitRequest request) {
        return visitService.create(principal.accountId(), profileId, request.toInput());
    }

    @GetMapping("/api/v1/visits/{visitId}")
    public VisitView get(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long visitId) {
        return visitService.get(principal.accountId(), visitId);
    }

    @PatchMapping("/api/v1/visits/{visitId}")
    public VisitView update(@AuthenticationPrincipal AccountPrincipal principal,
                            @PathVariable long visitId,
                            @Valid @RequestBody VisitRequest request) {
        if (request.version() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少版本号",
                    List.of(new FieldErrorItem("version", "NotNull", "缺少版本号")));
        }
        return visitService.update(principal.accountId(), visitId, request.version(), request.toInput());
    }

    @DeleteMapping("/api/v1/visits/{visitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long visitId) {
        visitService.delete(principal.accountId(), visitId);
    }

    @PutMapping("/api/v1/visits/{visitId}/reports/{reportId}")
    public VisitView link(@AuthenticationPrincipal AccountPrincipal principal,
                          @PathVariable long visitId, @PathVariable long reportId) {
        return visitService.linkReport(principal.accountId(), visitId, reportId);
    }

    @DeleteMapping("/api/v1/visits/{visitId}/reports/{reportId}")
    public VisitView unlink(@AuthenticationPrincipal AccountPrincipal principal,
                            @PathVariable long visitId, @PathVariable long reportId) {
        return visitService.unlinkReport(principal.accountId(), visitId, reportId);
    }
}
