package com.healthtrace.indicator.domain;

/**
 * FOLLOWED_METRIC 查询行：关注目标 code（标准指标 code 或 "TMP:{id}"）、名称、单位与展示顺序。
 * 标准指标的名称 / 单位取字典，临时指标取 TEMP_METRIC 的展示名与单位写法。
 */
public class FollowedMetric {

    private String metricCode;
    /** 临时指标 id；关注的是标准指标时为空。 */
    private Long tempMetricId;
    private String standardName;
    private String unit;
    private int sortOrder;

    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public Long getTempMetricId() { return tempMetricId; }
    public void setTempMetricId(Long tempMetricId) { this.tempMetricId = tempMetricId; }
    public String getStandardName() { return standardName; }
    public void setStandardName(String standardName) { this.standardName = standardName; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
