package com.healthtrace.rights.consent;

import java.time.LocalDateTime;

/** CONSENT_RECORD 行：只追加不修改，撤回以新增 WITHDRAWN 记录表达。 */
public class ConsentRecord {

    private Long id;
    private Long accountId;
    private Long profileId;
    private ConsentPurpose purpose;
    private SubjectRole subjectRole;
    private String noticeVersion;
    private ConsentDecision decision;
    private String providerScope;
    private LocalDateTime recordedAt;

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

    public ConsentPurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(ConsentPurpose purpose) {
        this.purpose = purpose;
    }

    public SubjectRole getSubjectRole() {
        return subjectRole;
    }

    public void setSubjectRole(SubjectRole subjectRole) {
        this.subjectRole = subjectRole;
    }

    public String getNoticeVersion() {
        return noticeVersion;
    }

    public void setNoticeVersion(String noticeVersion) {
        this.noticeVersion = noticeVersion;
    }

    public ConsentDecision getDecision() {
        return decision;
    }

    public void setDecision(ConsentDecision decision) {
        this.decision = decision;
    }

    public String getProviderScope() {
        return providerScope;
    }

    public void setProviderScope(String providerScope) {
        this.providerScope = providerScope;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }
}
