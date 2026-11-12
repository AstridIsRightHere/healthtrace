package com.healthtrace.indicator.service;

import com.healthtrace.indicator.domain.TempMetric;

/** 临时指标接口的响应结构（FR-009）。 */
public final class TempMetricViews {

    private TempMetricViews() {
    }

    /**
     * 设为临时指标的请求：observationId 为已确认报告中的一条未映射正式结果；follow 为 true 时同时加入关注列表。
     */
    public record CreateTempMetricRequest(Long observationId, Boolean follow) {
    }

    /**
     * 临时指标：code = "TMP:{id}"；unit 为单位写法，无单位为 null；
     * observationCount 只计已确认、正常可见报告中的正式结果；followed 表示已在关注列表中。
     */
    public record TempMetricView(String code, String name, String unit, long observationCount, boolean followed) {

        static TempMetricView from(TempMetric metric) {
            return new TempMetricView(TrendService.tempCode(metric.getId()), metric.getDisplayName(),
                    metric.getUnitText(), metric.getObservationCount(), metric.isFollowed());
        }
    }
}
