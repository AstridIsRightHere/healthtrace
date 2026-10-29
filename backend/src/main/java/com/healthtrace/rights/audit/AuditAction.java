package com.healthtrace.rights.audit;

/** 审计动作类别（docs/19 AUDIT_EVENT.action）。只追加，不重命名已有值：库中按名称存储。 */
public enum AuditAction {
    ACCOUNT_LOGIN,
    ACCOUNT_LOGOUT,
    PROFILE_CREATE,
    REPORT_CREATE,
    REPORT_PHOTO_UPLOAD,
    REPORT_OCR_SUBMIT,
    REPORT_CONFIRM,
    REPORT_REVISE,
    REPORT_DELETE,
    VISIT_CREATE,
    VISIT_UPDATE,
    VISIT_DELETE,
    VISIT_LINK_REPORT,
    VISIT_UNLINK_REPORT
}
