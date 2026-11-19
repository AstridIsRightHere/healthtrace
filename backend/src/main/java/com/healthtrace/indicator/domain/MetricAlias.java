package com.healthtrace.indicator.domain;

/** METRIC_ALIAS：标准指标的别名（docs/31 §2）。 */
public class MetricAlias {

    private Long id;
    private Long metricDefinitionId;
    private String alias;
    private String aliasNormalized;
    private String requiredUnit;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMetricDefinitionId() { return metricDefinitionId; }
    public void setMetricDefinitionId(Long metricDefinitionId) { this.metricDefinitionId = metricDefinitionId; }
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public String getAliasNormalized() { return aliasNormalized; }
    public void setAliasNormalized(String aliasNormalized) { this.aliasNormalized = aliasNormalized; }
    public String getRequiredUnit() { return requiredUnit; }
    public void setRequiredUnit(String requiredUnit) { this.requiredUnit = requiredUnit; }
}
