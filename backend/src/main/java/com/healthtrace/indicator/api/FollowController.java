package com.healthtrace.indicator.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.indicator.service.FollowService;
import com.healthtrace.indicator.service.FollowViews.FollowedMetricView;
import com.healthtrace.indicator.service.FollowViews.MetricDefinitionView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 关注指标与指标字典（FR-009）。需登录，写操作需 CSRF；他人档案统一 404 HT-3001。 */
@RestController
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    /** 完整有序列表；校验（空值、数量上限、重复、未知 code）在服务层完成。 */
    public record FollowRequest(List<String> metricCodes) {
    }

    @GetMapping("/api/v1/patient-profiles/{profileId}/followed-metrics")
    public List<FollowedMetricView> list(@AuthenticationPrincipal AccountPrincipal principal,
                                         @PathVariable long profileId) {
        return followService.list(principal.accountId(), profileId);
    }

    @PostMapping("/api/v1/patient-profiles/{profileId}/followed-metrics")
    public List<FollowedMetricView> replace(@AuthenticationPrincipal AccountPrincipal principal,
                                            @PathVariable long profileId,
                                            @RequestBody FollowRequest request) {
        return followService.replace(principal.accountId(), profileId, request.metricCodes());
    }

    @GetMapping("/api/v1/metric-definitions")
    public List<MetricDefinitionView> definitions() {
        return followService.listDefinitions();
    }
}
