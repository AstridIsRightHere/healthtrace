package com.healthtrace.rights.deletion;

import java.time.LocalDateTime;

/** DELETION_LEDGER 行：只保存不可逆摘要与状态，不含明文 id、邮箱或病历内容。 */
public class DeletionLedgerEntry {

    public static final String COMPLETED = "COMPLETED";
    public static final String STORAGE_PENDING = "STORAGE_PENDING";

    private Long id;
    private DeletionEventType eventType;
    private String subjectDigest;
    private Integer itemCount;
    private String status;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public DeletionEventType getEventType() {
        return eventType;
    }

    public void setEventType(DeletionEventType eventType) {
        this.eventType = eventType;
    }

    public String getSubjectDigest() {
        return subjectDigest;
    }

    public void setSubjectDigest(String subjectDigest) {
        this.subjectDigest = subjectDigest;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
