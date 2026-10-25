package com.healthtrace.report.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 报告实体（docs/19 REPORT）。时间字段均为 UTC；examinationDate 为检查日，与上传时间 createdAt 分别记录。 */
public class Report {

    private Long id;
    private Long profileId;
    private Long createdBy;
    private String title;
    private ReportType reportType;
    private LocalDate examinationDate;
    private ProcessingStatus processingStatus;
    private VisibilityStatus visibilityStatus;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private long version;
    private String sourceHospitalText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProfileId() {
        return profileId;
    }

    public void setProfileId(Long profileId) {
        this.profileId = profileId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ReportType getReportType() {
        return reportType;
    }

    public void setReportType(ReportType reportType) {
        this.reportType = reportType;
    }

    public LocalDate getExaminationDate() {
        return examinationDate;
    }

    public void setExaminationDate(LocalDate examinationDate) {
        this.examinationDate = examinationDate;
    }

    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(ProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public VisibilityStatus getVisibilityStatus() {
        return visibilityStatus;
    }

    public void setVisibilityStatus(VisibilityStatus visibilityStatus) {
        this.visibilityStatus = visibilityStatus;
    }

    public Long getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(Long confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getSourceHospitalText() {
        return sourceHospitalText;
    }

    public void setSourceHospitalText(String sourceHospitalText) {
        this.sourceHospitalText = sourceHospitalText;
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
