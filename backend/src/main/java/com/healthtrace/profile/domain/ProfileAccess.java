package com.healthtrace.profile.domain;

import java.time.LocalDateTime;

/** 账号与档案的授权关系（docs/19 PROFILE_ACCESS）。时间字段均为 UTC。 */
public class ProfileAccess {

    private Long id;
    private Long accountId;
    private Long profileId;
    private Relationship relationship;
    private AccessStatus accessStatus;
    private LocalDateTime authorizationDeclaredAt;
    private String declarationVersion;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getProfileId() {
        return profileId;
    }

    public void setProfileId(Long profileId) {
        this.profileId = profileId;
    }

    public Relationship getRelationship() {
        return relationship;
    }

    public void setRelationship(Relationship relationship) {
        this.relationship = relationship;
    }

    public AccessStatus getAccessStatus() {
        return accessStatus;
    }

    public void setAccessStatus(AccessStatus accessStatus) {
        this.accessStatus = accessStatus;
    }

    public LocalDateTime getAuthorizationDeclaredAt() {
        return authorizationDeclaredAt;
    }

    public void setAuthorizationDeclaredAt(LocalDateTime authorizationDeclaredAt) {
        this.authorizationDeclaredAt = authorizationDeclaredAt;
    }

    public String getDeclarationVersion() {
        return declarationVersion;
    }

    public void setDeclarationVersion(String declarationVersion) {
        this.declarationVersion = declarationVersion;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
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
