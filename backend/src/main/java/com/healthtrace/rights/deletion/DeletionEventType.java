package com.healthtrace.rights.deletion;

/** 删除账本事件类型（V9 deletion_ledger.event_type）。只追加，不改名。 */
public enum DeletionEventType {
    /** FR-016 报告彻底删除。 */
    REPORT_PURGE,
    /** FR-017 账号注销。 */
    ACCOUNT_CLOSURE
}
