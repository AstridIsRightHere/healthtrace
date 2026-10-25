package com.healthtrace.account.service;

import com.healthtrace.account.domain.AccountStatus;

/**
 * 返回给账号本人的视图。不含密码哈希、索引或内部时间戳。
 */
public record AccountView(long id, String email, boolean emailVerified, AccountStatus status) {
}
