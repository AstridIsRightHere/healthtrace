package com.healthtrace.indicator.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.indicator.service.TempMetricService;
import com.healthtrace.indicator.service.TempMetricViews.CreateTempMetricRequest;
import com.healthtrace.indicator.service.TempMetricViews.TempMetricView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 临时指标（FR-009，DEC-015）。需登录，写操作需 CSRF；他人档案、结果或临时指标统一 404 HT-3001。 */
@RestController
public class TempMetricController {

    private final TempMetricService tempMetricService;

    public TempMetricController(TempMetricService tempMetricService) {
        this.tempMetricService = tempMetricService;
    }

    @GetMapping("/api/v1/patient-profiles/{profileId}/temporary-metrics")
    public List<TempMetricView> list(@AuthenticationPrincipal AccountPrincipal principal,
                                     @PathVariable long profileId) {
        return tempMetricService.list(principal.accountId(), profileId);
    }

    /** 把一条已确认的未映射结果设为临时指标；同名同单位自动合并，重复提交幂等。 */
    @PostMapping("/api/v1/patient-profiles/{profileId}/temporary-metrics")
    @ResponseStatus(HttpStatus.CREATED)
    public TempMetricView create(@AuthenticationPrincipal AccountPrincipal principal,
                                 @PathVariable long profileId,
                                 @RequestBody CreateTempMetricRequest request) {
        return tempMetricService.create(principal.accountId(), profileId, request.observationId(), request.follow());
    }

    /** 删除临时指标：解除结果映射与关注，结果本身保留。 */
    @DeleteMapping("/api/v1/patient-profiles/{profileId}/temporary-metrics/{tempMetricId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AccountPrincipal principal,
                       @PathVariable long profileId,
                       @PathVariable long tempMetricId) {
        tempMetricService.delete(principal.accountId(), profileId, tempMetricId);
    }
}
