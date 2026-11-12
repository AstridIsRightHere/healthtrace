package com.healthtrace.indicator.domain;

import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.VisibilityStatus;

/** 设为 / 归并临时指标时读取的结果行：所属档案、报告状态、映射现状与归并键来源（原名、单位）。 */
public class TempMetricCandidate {

    private Long observationId;
    private Long reportId;
    private Long profileId;
    private ObservationStatus status;
    private ProcessingStatus processingStatus;
    private VisibilityStatus visibilityStatus;
    private Long metricDefinitionId;
    private Long tempMetricId;
    private String originalName;
    private String unitText;

    public Long getObservationId() { return observationId; }
    public void setObservationId(Long observationId) { this.observationId = observationId; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public Long getProfileId() { return profileId; }
    public void setProfileId(Long profileId) { this.profileId = profileId; }
    public ObservationStatus getStatus() { return status; }
    public void setStatus(ObservationStatus status) { this.status = status; }
    public ProcessingStatus getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(ProcessingStatus processingStatus) { this.processingStatus = processingStatus; }
    public VisibilityStatus getVisibilityStatus() { return visibilityStatus; }
    public void setVisibilityStatus(VisibilityStatus visibilityStatus) { this.visibilityStatus = visibilityStatus; }
    public Long getMetricDefinitionId() { return metricDefinitionId; }
    public void setMetricDefinitionId(Long metricDefinitionId) { this.metricDefinitionId = metricDefinitionId; }
    public Long getTempMetricId() { return tempMetricId; }
    public void setTempMetricId(Long tempMetricId) { this.tempMetricId = tempMetricId; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getUnitText() { return unitText; }
    public void setUnitText(String unitText) { this.unitText = unitText; }
}
