package com.healthtrace.report.domain;

import java.time.LocalDateTime;

/** OCR_JOB 行。原文字段为密文，只在服务层解密。 */
public class OcrJob {

    private Long id;
    private Long reportId;
    private String provider;
    private Long consentRecordId;
    private LocalDateTime consentDeclaredAt;
    private Long createdBy;
    private OcrJobStatus status;
    private int attemptCount;
    private String providerRequestId;
    private byte[] rawTextCiphertext;
    private byte[] rawExtractionCiphertext;
    private String errorCode;
    private LocalDateTime nextRetryAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
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

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Long getConsentRecordId() {
        return consentRecordId;
    }

    public void setConsentRecordId(Long consentRecordId) {
        this.consentRecordId = consentRecordId;
    }

    public LocalDateTime getConsentDeclaredAt() {
        return consentDeclaredAt;
    }

    public void setConsentDeclaredAt(LocalDateTime consentDeclaredAt) {
        this.consentDeclaredAt = consentDeclaredAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public OcrJobStatus getStatus() {
        return status;
    }

    public void setStatus(OcrJobStatus status) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public byte[] getRawTextCiphertext() {
        return rawTextCiphertext;
    }

    public void setRawTextCiphertext(byte[] rawTextCiphertext) {
        this.rawTextCiphertext = rawTextCiphertext;
    }

    public byte[] getRawExtractionCiphertext() {
        return rawExtractionCiphertext;
    }

    public void setRawExtractionCiphertext(byte[] rawExtractionCiphertext) {
        this.rawExtractionCiphertext = rawExtractionCiphertext;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
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
