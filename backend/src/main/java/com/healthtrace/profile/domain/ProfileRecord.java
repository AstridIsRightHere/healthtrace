package com.healthtrace.profile.domain;

import java.time.LocalDateTime;

/** 查询投影：档案 + 当前账号对它的有效授权（patient_profile JOIN profile_access）。 */
public class ProfileRecord {

    private Long profileId;
    private String nickname;
    private ProfileStatus status;
    private long version;
    private Relationship relationship;
    private LocalDateTime authorizationDeclaredAt;
    private String declarationVersion;

    public Long getProfileId() {
        return profileId;
    }

    public void setProfileId(Long profileId) {
        this.profileId = profileId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public ProfileStatus getStatus() {
        return status;
    }

    public void setStatus(ProfileStatus status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Relationship getRelationship() {
        return relationship;
    }

    public void setRelationship(Relationship relationship) {
        this.relationship = relationship;
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
}
