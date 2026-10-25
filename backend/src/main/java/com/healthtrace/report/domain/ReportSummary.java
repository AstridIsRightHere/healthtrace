package com.healthtrace.report.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 待处理列表投影：报告 + 完整图片数 + 首图（按页序最小）。 */
public class ReportSummary {

    private Long id;
    private String title;
    private ReportType reportType;
    private LocalDate examinationDate;
    private ProcessingStatus processingStatus;
    private LocalDateTime createdAt;
    private int photoCount;
    private Long coverPhotoId;
    private boolean coverHasPreview;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public int getPhotoCount() {
        return photoCount;
    }

    public void setPhotoCount(int photoCount) {
        this.photoCount = photoCount;
    }

    public Long getCoverPhotoId() {
        return coverPhotoId;
    }

    public void setCoverPhotoId(Long coverPhotoId) {
        this.coverPhotoId = coverPhotoId;
    }

    public boolean getCoverHasPreview() {
        return coverHasPreview;
    }

    public void setCoverHasPreview(boolean coverHasPreview) {
        this.coverHasPreview = coverHasPreview;
    }
}
