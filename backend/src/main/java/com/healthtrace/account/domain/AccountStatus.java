package com.healthtrace.account.domain;

/**
 * 账号状态（docs/19 ACCOUNT.status：未验证/有效/冻结/注销中/注销）。
 */
public enum AccountStatus {
    /** 未验证：可登录，但禁止真实上传与找回密码（FR-001）。 */
    UNVERIFIED,
    /** 有效。 */
    ACTIVE,
    /** 冻结。 */
    FROZEN,
    /** 注销中。 */
    CLOSING,
    /** 注销。 */
    CLOSED
}
