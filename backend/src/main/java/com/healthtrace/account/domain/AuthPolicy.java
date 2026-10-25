package com.healthtrace.account.domain;

import java.time.Duration;

/**
 * 认证安全参数（DEC-017，2026-10-20 确认）。
 * 以常量固定而非外部配置：这些值是契约基线，改动必须走决策流程，不能靠环境变量悄悄放宽。
 */
public final class AuthPolicy {

    /** BCrypt 工作因子。 */
    public static final int BCRYPT_COST = 12;
    /** 密码最小长度（按 Unicode 码点计），不强制复杂度。 */
    public static final int PASSWORD_MIN_LENGTH = 12;
    /** BCrypt 只处理前 72 字节，超出部分会被静默截断，因此按 UTF-8 字节数设上限。 */
    public static final int PASSWORD_MAX_BYTES = 72;

    /** 邮箱验证令牌有效期。 */
    public static final Duration EMAIL_VERIFICATION_TOKEN_TTL = Duration.ofHours(24);
    /** 密码重置令牌有效期。 */
    public static final Duration PASSWORD_RESET_TOKEN_TTL = Duration.ofHours(1);

    /** 会话绝对超时：从登录起算，活动不能延长（不活跃 30 分钟由 server.servlet.session.timeout 配置）。 */
    public static final Duration SESSION_ABSOLUTE_TIMEOUT = Duration.ofHours(8);

    /** 连续登录失败上限。 */
    public static final int MAX_FAILED_LOGINS = 5;
    /** 达到上限后的锁定时长。 */
    public static final Duration LOGIN_LOCK_DURATION = Duration.ofMinutes(15);

    /** CSRF 令牌有效期。 */
    public static final Duration CSRF_TOKEN_TTL = Duration.ofHours(2);

    private AuthPolicy() {
    }
}
