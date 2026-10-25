package com.healthtrace.report.domain;

/** OCR_JOB.status：等待/运行/成功/失败/取消（docs/19）。 */
public enum OcrJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isActive() {
        return this == PENDING || this == RUNNING;
    }
}
