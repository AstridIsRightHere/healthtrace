package com.healthtrace.report.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.report.service.ReviewService;
import com.healthtrace.report.service.ReviewViews.ConfirmCommand;
import com.healthtrace.report.service.ReviewViews.ConfirmResult;
import com.healthtrace.report.service.ReviewViews.DraftReport;
import com.healthtrace.report.service.ReviewViews.DraftView;
import com.healthtrace.report.service.ReviewViews.OcrJobView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * OCR 识别、草稿详情与整页确认（FR-006、FR-007、FR-014、FR-015）。
 * 所有端点需登录，写操作需 CSRF 令牌；无权访问与不存在统一 404。
 */
@RestController
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 提交 OCR 识别。必须显式携带 ocrConsent=true（NFR-002），否则 403 HT-4003，且不会调用第三方。
     */
    @PostMapping("/api/v1/reports/{reportId}/ocr-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OcrJobView submitOcr(@AuthenticationPrincipal AccountPrincipal principal,
                                @PathVariable long reportId,
                                @RequestParam(name = "ocrConsent", required = false) Boolean ocrConsent) {
        return reviewService.submitOcr(principal.accountId(), reportId, Boolean.TRUE.equals(ocrConsent));
    }

    @GetMapping("/api/v1/reports/{reportId}/ocr-jobs/latest")
    public OcrJobView latestJob(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long reportId) {
        return reviewService.getLatestJob(principal.accountId(), reportId);
    }

    /** 拒绝第三方识别或识别失败后转为手动录入。 */
    @PostMapping("/api/v1/reports/{reportId}/manual-entry")
    public DraftReport manualEntry(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long reportId) {
        return reviewService.startManualEntry(principal.accountId(), reportId);
    }

    @GetMapping("/api/v1/reports/{reportId}/draft")
    public DraftView draft(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long reportId) {
        return reviewService.getDraft(principal.accountId(), reportId);
    }

    /** 整页确认：草稿转为正式记录，报告进入 CONFIRMED；version 用于乐观锁。 */
    @PostMapping("/api/v1/reports/{reportId}/confirmation")
    public ConfirmResult confirm(@AuthenticationPrincipal AccountPrincipal principal,
                                 @PathVariable long reportId,
                                 @RequestBody ConfirmCommand command) {
        return reviewService.confirm(principal.accountId(), reportId, command);
    }
}
