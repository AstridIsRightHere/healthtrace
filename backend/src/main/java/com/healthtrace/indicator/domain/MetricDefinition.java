package com.healthtrace.indicator.domain;

/** METRIC_DEFINITION：MVP 18 项标准指标（docs/31 §2）。standardUnit 只用于展示与提示，不做换算。 */
public class MetricDefinition {

    private Long id;
    private String code;
    private String dictionaryId;
    private String nationalCode;
    private String standardName;
    private String standardUnit;
    private String reportType;
    private String comparabilityNote;
    private String dictionaryVersion;
    private int sortOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDictionaryId() { return dictionaryId; }
    public void setDictionaryId(String dictionaryId) { this.dictionaryId = dictionaryId; }
    public String getNationalCode() { return nationalCode; }
    public void setNationalCode(String nationalCode) { this.nationalCode = nationalCode; }
    public String getStandardName() { return standardName; }
    public void setStandardName(String standardName) { this.standardName = standardName; }
    public String getStandardUnit() { return standardUnit; }
    public void setStandardUnit(String standardUnit) { this.standardUnit = standardUnit; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public String getComparabilityNote() { return comparabilityNote; }
    public void setComparabilityNote(String comparabilityNote) { this.comparabilityNote = comparabilityNote; }
    public String getDictionaryVersion() { return dictionaryVersion; }
    public void setDictionaryVersion(String dictionaryVersion) { this.dictionaryVersion = dictionaryVersion; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
