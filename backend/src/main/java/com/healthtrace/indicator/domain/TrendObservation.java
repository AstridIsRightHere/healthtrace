package com.healthtrace.indicator.domain;

import com.healthtrace.report.domain.AbnormalFlag;
import com.healthtrace.report.domain.ResultKind;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 趋势计算用的正式观测值（已确认报告中的 FORMAL 行）。
 * metricCode 等字段仅在“有数据指标列表”查询中填充。
 */
public class TrendObservation {

    private Long observationId;
    private Long reportId;
    private String metricCode;
    private LocalDate examinationDate;
    private String originalValue;
    private ResultKind resultKind;
    private BigDecimal numericValue;
    private String unitText;
    private String referenceText;
    private BigDecimal referenceLow;
    private BigDecimal referenceHigh;
    private AbnormalFlag abnormalFlag;
    private String specimenText;
    private String methodText;

    public Long getObservationId() { return observationId; }
    public void setObservationId(Long observationId) { this.observationId = observationId; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public LocalDate getExaminationDate() { return examinationDate; }
    public void setExaminationDate(LocalDate examinationDate) { this.examinationDate = examinationDate; }
    public String getOriginalValue() { return originalValue; }
    public void setOriginalValue(String originalValue) { this.originalValue = originalValue; }
    public ResultKind getResultKind() { return resultKind; }
    public void setResultKind(ResultKind resultKind) { this.resultKind = resultKind; }
    public BigDecimal getNumericValue() { return numericValue; }
    public void setNumericValue(BigDecimal numericValue) { this.numericValue = numericValue; }
    public String getUnitText() { return unitText; }
    public void setUnitText(String unitText) { this.unitText = unitText; }
    public String getReferenceText() { return referenceText; }
    public void setReferenceText(String referenceText) { this.referenceText = referenceText; }
    public BigDecimal getReferenceLow() { return referenceLow; }
    public void setReferenceLow(BigDecimal referenceLow) { this.referenceLow = referenceLow; }
    public BigDecimal getReferenceHigh() { return referenceHigh; }
    public void setReferenceHigh(BigDecimal referenceHigh) { this.referenceHigh = referenceHigh; }
    public AbnormalFlag getAbnormalFlag() { return abnormalFlag; }
    public void setAbnormalFlag(AbnormalFlag abnormalFlag) { this.abnormalFlag = abnormalFlag; }
    public String getSpecimenText() { return specimenText; }
    public void setSpecimenText(String specimenText) { this.specimenText = specimenText; }
    public String getMethodText() { return methodText; }
    public void setMethodText(String methodText) { this.methodText = methodText; }
}
