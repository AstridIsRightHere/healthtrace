package com.healthtrace.report.domain;

/** 原图存储状态（docs/19 PHOTO.storage_status）：待上传/完整/失败/删除中。 */
public enum StorageStatus {
    PENDING,
    COMPLETE,
    FAILED,
    DELETING
}
