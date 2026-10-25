package com.healthtrace.account.domain;

import java.time.LocalDateTime;

/**
 * 账号实体（docs/19 ACCOUNT）。时间字段均为 UTC。
 * 邮箱拆为两列：emailCiphertext 为 AES-GCM 加密载荷，emailIndex 为规范化邮箱的 HMAC，用于检索与唯一约束。
 */
public class Account {

    private Long id;
    private byte[] emailCiphertext;
    private String emailIndex;
    private String passwordHash;
    private LocalDateTime emailVerifiedAt;
    private AccountStatus status;
    private LocalDateTime closedAt;
    private int failedLoginCount;
    private LocalDateTime lockedUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** DEC-017：锁定期内拒绝登录。 */
    public boolean isLockedAt(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public void setFailedLoginCount(int failedLoginCount) {
        this.failedLoginCount = failedLoginCount;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public byte[] getEmailCiphertext() {
        return emailCiphertext;
    }

    public void setEmailCiphertext(byte[] emailCiphertext) {
        this.emailCiphertext = emailCiphertext;
    }

    public String getEmailIndex() {
        return emailIndex;
    }

    public void setEmailIndex(String emailIndex) {
        this.emailIndex = emailIndex;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public LocalDateTime getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(LocalDateTime emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
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
