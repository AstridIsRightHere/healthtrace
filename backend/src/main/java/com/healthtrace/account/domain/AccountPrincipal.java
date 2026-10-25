package com.healthtrace.account.domain;

import java.io.Serializable;

/**
 * 会话中的登录身份。只保存账号 ID，不保存邮箱或密码摘要；需要账号状态时实时查库。
 */
public record AccountPrincipal(long accountId) implements Serializable {
}
