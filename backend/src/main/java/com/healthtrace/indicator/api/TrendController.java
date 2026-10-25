package com.healthtrace.indicator.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.indicator.service.TrendService;
import com.healthtrace.indicator.service.TrendViews.MetricSummary;
import com.healthtrace.indicator.service.TrendViews.TrendView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 指标趋势（FR-010/011）。只读接口，需登录；他人档案统一 404 HT-3001。 */
@RestController
@RequestMapping("/api/v1/patient-profiles/{profileId}")
public class TrendController {

    private final TrendService trendService;

    public TrendController(TrendService trendService) {
        this.trendService = trendService;
    }

    @GetMapping("/metrics")
    public List<MetricSummary> metrics(@AuthenticationPrincipal AccountPrincipal principal,
                                       @PathVariable long profileId) {
        return trendService.listMetrics(principal.accountId(), profileId);
    }

    @GetMapping("/trends")
    public TrendView trend(@AuthenticationPrincipal AccountPrincipal principal,
                           @PathVariable long profileId,
                           @RequestParam(required = false) String metricCode,
                           @RequestParam(required = false) String range,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return trendService.getTrend(principal.accountId(), profileId, metricCode, range, from, to);
    }
}
