package com.healthtrace.account.domain;

import java.time.Duration;

/**
 * 认证令牌用途（docs/19 AUTH_TOKEN.purpose：邮箱验证/密码恢复）。
 */
public enum TokenPurpose {
    EMAIL_VERIFICATION(AuthPolicy.EMAIL_VERIFICATION_TOKEN_TTL),
    PASSWORD_RESET(AuthPolicy.PASSWORD_RESET_TOKEN_TTL);

    private final Duration ttl;

    TokenPurpose(Duration ttl) {
        this.ttl = ttl;
    }

    /** 该用途令牌的有效期（DEC-017）。 */
    public Duration ttl() {
        return ttl;
    }
}
