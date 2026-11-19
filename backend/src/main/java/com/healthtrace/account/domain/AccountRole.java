package com.healthtrace.account.domain;

/**
 * 账号角色（FR-018）。
 * USER：普通患者用户；ADMIN：管理员，仅执行字典与邀请码维护，不得访问患者健康数据。
 */
public enum AccountRole {
    USER,
    ADMIN
}
