package com.healthtrace.indicator.service;

import com.healthtrace.indicator.domain.FollowedMetric;
import com.healthtrace.indicator.domain.MetricDefinition;

/** 关注指标接口的响应结构（FR-009）。 */
public final class FollowViews {

    private FollowViews() {
    }

    /**
     * 关注指标：code（标准指标 code 或临时指标 "TMP:{id}"）、名称、单位、从 0 开始的展示顺序。
     * unit 对标准指标为字典标准单位，对临时指标为其单位写法（无单位为 null）；temporary 标记临时指标。
     */
    public record FollowedMetricView(String metricCode, String name, int sortOrder, String unit, boolean temporary) {

        static FollowedMetricView from(FollowedMetric row) {
            return new FollowedMetricView(row.getMetricCode(), row.getStandardName(), row.getSortOrder(),
                    row.getUnit(), row.getTempMetricId() != null);
        }
    }

    /** 标准指标字典条目，供“管理关注”选择。 */
    public record MetricDefinitionView(String code, String name) {

        static MetricDefinitionView from(MetricDefinition definition) {
            return new MetricDefinitionView(definition.getCode(), definition.getStandardName());
        }
    }
}
