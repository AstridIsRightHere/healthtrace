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
    /** 最近一次 OCR 任务的错误码（无任务或成功为 null），供待处理列表提示失败（FR-015）。 */
    private String lastOcrErrorCode;
    /** 列表移除时间（仅回收站查询填充，其他列表为 null）。 */
    private LocalDateTime removedAt;

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

    public String getLastOcrErrorCode() {
        return lastOcrErrorCode;
    }

    public void setLastOcrErrorCode(String lastOcrErrorCode) {
        this.lastOcrErrorCode = lastOcrErrorCode;
    }

    public LocalDateTime getRemovedAt() {
        return removedAt;
    }

    public void setRemovedAt(LocalDateTime removedAt) {
        this.removedAt = removedAt;
    }
}
