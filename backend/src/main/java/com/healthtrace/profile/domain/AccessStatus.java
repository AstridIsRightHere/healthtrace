package com.healthtrace.profile.domain;

/** 账号对档案的授权状态（docs/19 PROFILE_ACCESS.access_status）：有效 / 撤回 / 冻结。只有 ACTIVE 可访问。 */
public enum AccessStatus {
    ACTIVE,
    REVOKED,
    FROZEN
}
