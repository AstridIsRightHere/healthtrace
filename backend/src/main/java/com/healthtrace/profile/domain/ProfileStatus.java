package com.healthtrace.profile.domain;

/** 患者档案状态（docs/19 PATIENT_PROFILE.status）：有效 / 授权暂停 / 删除中。 */
public enum ProfileStatus {
    ACTIVE,
    AUTH_SUSPENDED,
    DELETING
}
