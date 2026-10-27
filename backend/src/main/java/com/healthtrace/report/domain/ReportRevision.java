package com.healthtrace.report.domain;

import java.time.LocalDateTime;

/** REPORT_REVISION 行（FR-014）：change_payload 为加密后的前后差异 JSON，只追加不修改。 */
public class ReportRevision {

    private Long id;
    private Long reportId;
    private Long actorAccountId;
    private long fromVersion;
    private long toVersion;
    private byte[] changePayload;
    private String reason;
    private LocalDateTime createdAt;

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

    public Long getActorAccountId() {
        return actorAccountId;
    }

    public void setActorAccountId(Long actorAccountId) {
        this.actorAccountId = actorAccountId;
    }

    public long getFromVersion() {
        return fromVersion;
    }

    public void setFromVersion(long fromVersion) {
        this.fromVersion = fromVersion;
    }

    public long getToVersion() {
        return toVersion;
    }

    public void setToVersion(long toVersion) {
        this.toVersion = toVersion;
    }

    public byte[] getChangePayload() {
        return changePayload;
    }

    public void setChangePayload(byte[] changePayload) {
        this.changePayload = changePayload;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
