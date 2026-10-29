package com.healthtrace.indicator.domain;

/** FOLLOWED_METRIC 查询行：关注指标 code、字典标准名称与展示顺序。 */
public class FollowedMetric {

    private String metricCode;
    private String standardName;
    private int sortOrder;

    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getStandardName() { return standardName; }
    public void setStandardName(String standardName) { this.standardName = standardName; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
