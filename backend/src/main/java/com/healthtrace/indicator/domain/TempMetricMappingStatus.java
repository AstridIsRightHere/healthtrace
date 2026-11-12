package com.healthtrace.indicator.domain;

/** TEMP_METRIC.mapping_status（docs/19，DEC-015）：待归并 / 已归并 / 分开展示。 */
public enum TempMetricMappingStatus {
    /** 只有发起创建的结果，尚未与其他结果归并。 */
    UNMERGED,
    /** 已按“同名同单位同档案”自动归并了多条结果。 */
    MERGED,
    /** 用户选择分开展示（MVP 暂不提供入口，预留）。 */
    SEPARATE
}
