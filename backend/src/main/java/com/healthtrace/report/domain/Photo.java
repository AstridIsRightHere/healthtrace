package com.healthtrace.report.domain;

import java.time.LocalDateTime;

/** 原图实体（docs/19 PHOTO）。objectKey 是私有对象键，不是公开 URL 或本地路径。 */
public class Photo {

    private Long id;
    private Long reportId;
    private Long profileId;
    private String objectKey;
    private String contentDigest;
    private String mediaType;
    private long byteSize;
    private String previewObjectKey;
    private String previewMediaType;
    private Long previewByteSize;
    private int pageOrder;
    private StorageStatus storageStatus;
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

    public Long getProfileId() {
        return profileId;
    }

    public void setProfileId(Long profileId) {
        this.profileId = profileId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getContentDigest() {
        return contentDigest;
    }

    public void setContentDigest(String contentDigest) {
        this.contentDigest = contentDigest;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }

    public String getPreviewObjectKey() {
        return previewObjectKey;
    }

    public void setPreviewObjectKey(String previewObjectKey) {
        this.previewObjectKey = previewObjectKey;
    }

    public String getPreviewMediaType() {
        return previewMediaType;
    }

    public void setPreviewMediaType(String previewMediaType) {
        this.previewMediaType = previewMediaType;
    }

    public Long getPreviewByteSize() {
        return previewByteSize;
    }

    public void setPreviewByteSize(Long previewByteSize) {
        this.previewByteSize = previewByteSize;
    }

    public int getPageOrder() {
        return pageOrder;
    }

    public void setPageOrder(int pageOrder) {
        this.pageOrder = pageOrder;
    }

    public StorageStatus getStorageStatus() {
        return storageStatus;
    }

    public void setStorageStatus(StorageStatus storageStatus) {
        this.storageStatus = storageStatus;
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
