package com.healthtrace.report.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.report.compare.ComparisonViews.ComparisonView;
import com.healthtrace.report.compare.ReportComparisonService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 报告对比（FR-010/011）。只读接口，需登录；不可访问或未确认的报告统一 404 HT-3001。 */
@RestController
public class ReportComparisonController {

    private final ReportComparisonService comparisonService;

    public ReportComparisonController(ReportComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    @GetMapping("/api/v1/report-comparisons")
    public ComparisonView compare(@AuthenticationPrincipal AccountPrincipal principal,
                                  @RequestParam long baseReportId,
                                  @RequestParam long targetReportId) {
        return comparisonService.compare(principal.accountId(), baseReportId, targetReportId);
    }
}
