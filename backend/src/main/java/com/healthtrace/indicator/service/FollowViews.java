package com.healthtrace.indicator.service;

import com.healthtrace.indicator.domain.FollowedMetric;
import com.healthtrace.indicator.domain.MetricDefinition;

/** 关注指标接口的响应结构（FR-009）。 */
public final class FollowViews {

    private FollowViews() {
    }

    /** 关注指标：code、字典标准名称、从 0 开始的展示顺序。 */
    public record FollowedMetricView(String metricCode, String name, int sortOrder) {

        static FollowedMetricView from(FollowedMetric row) {
            return new FollowedMetricView(row.getMetricCode(), row.getStandardName(), row.getSortOrder());
        }
    }

    /** 标准指标字典条目，供“管理关注”选择。 */
    public record MetricDefinitionView(String code, String name) {

        static MetricDefinitionView from(MetricDefinition definition) {
            return new MetricDefinitionView(definition.getCode(), definition.getStandardName());
        }
    }
}
