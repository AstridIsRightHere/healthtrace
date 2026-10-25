package com.healthtrace.account.domain;

import java.time.LocalDateTime;

/**
 * 邀请码实体（docs/19 INVITATION）。只保存邀请码摘要，不保存原码。
 */
public class Invitation {

    private Long id;
    private Long issuerAccountId;
    private String tokenDigest;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private Long usedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 未使用且未过期（expiresAt 为空表示不限期）。 */
    public boolean isUsableAt(LocalDateTime now) {
        return usedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIssuerAccountId() {
        return issuerAccountId;
    }

    public void setIssuerAccountId(Long issuerAccountId) {
        this.issuerAccountId = issuerAccountId;
    }

    public String getTokenDigest() {
        return tokenDigest;
    }

    public void setTokenDigest(String tokenDigest) {
        this.tokenDigest = tokenDigest;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
    }

    public Long getUsedBy() {
        return usedBy;
    }

    public void setUsedBy(Long usedBy) {
        this.usedBy = usedBy;
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
