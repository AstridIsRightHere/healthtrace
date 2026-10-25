package com.healthtrace.report.domain;

/** 报告处理状态（docs/19 REPORT.processing_status）：上传/识别/待确认/确认/失败。 */
public enum ProcessingStatus {
    UPLOADING,
    RECOGNIZING,
    PENDING_CONFIRMATION,
    CONFIRMED,
    FAILED
}
