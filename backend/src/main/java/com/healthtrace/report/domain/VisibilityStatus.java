package com.healthtrace.report.domain;

/** 报告可见状态（docs/19 REPORT.visibility_status）：正常/列表移除/删除中/已删除。 */
public enum VisibilityStatus {
    NORMAL,
    LIST_REMOVED,
    DELETING,
    DELETED
}
