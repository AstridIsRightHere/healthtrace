package com.healthtrace.report.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** OBSERVATION 行：报告单上的一个检验项目。originalName/originalValue 始终保留原文。 */
public class Observation {

    private Long id;
    private Long reportId;
    private String originalName;
    private String originalValue;
    private ResultKind resultKind;
    private BigDecimal numericValue;
    private String unitText;
    private String referenceText;
    private BigDecimal referenceLow;
    private BigDecimal referenceHigh;
    private AbnormalFlag abnormalFlag;
    private LocalDate examinationDate;
    private BigDecimal confidence;
    private LocalDateTime confirmedAt;
    private Long sourcePhotoId;
    private ObservationStatus status;
    private int rowOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getOriginalValue() {
        return originalValue;
    }

    public void setOriginalValue(String originalValue) {
        this.originalValue = originalValue;
    }

    public ResultKind getResultKind() {
        return resultKind;
    }

    public void setResultKind(ResultKind resultKind) {
        this.resultKind = resultKind;
    }

    public BigDecimal getNumericValue() {
        return numericValue;
    }

    public void setNumericValue(BigDecimal numericValue) {
        this.numericValue = numericValue;
    }

    public String getUnitText() {
        return unitText;
    }

    public void setUnitText(String unitText) {
        this.unitText = unitText;
    }

    public String getReferenceText() {
        return referenceText;
    }

    public void setReferenceText(String referenceText) {
        this.referenceText = referenceText;
    }

    public BigDecimal getReferenceLow() {
        return referenceLow;
    }

    public void setReferenceLow(BigDecimal referenceLow) {
        this.referenceLow = referenceLow;
    }

    public BigDecimal getReferenceHigh() {
        return referenceHigh;
    }

    public void setReferenceHigh(BigDecimal referenceHigh) {
        this.referenceHigh = referenceHigh;
    }

    public AbnormalFlag getAbnormalFlag() {
        return abnormalFlag;
    }

    public void setAbnormalFlag(AbnormalFlag abnormalFlag) {
        this.abnormalFlag = abnormalFlag;
    }

    public LocalDate getExaminationDate() {
        return examinationDate;
    }

    public void setExaminationDate(LocalDate examinationDate) {
        this.examinationDate = examinationDate;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Long getSourcePhotoId() {
        return sourcePhotoId;
    }

    public void setSourcePhotoId(Long sourcePhotoId) {
        this.sourcePhotoId = sourcePhotoId;
    }

    public ObservationStatus getStatus() {
        return status;
    }

    public void setStatus(ObservationStatus status) {
        this.status = status;
    }

    public int getRowOrder() {
        return rowOrder;
    }

    public void setRowOrder(int rowOrder) {
        this.rowOrder = rowOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
