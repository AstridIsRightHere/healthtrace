package com.healthtrace.common.web;

import org.springframework.http.HttpStatus;

/**
 * 稳定业务错误码（docs/08 API 错误响应规范 · 错误码分段）。码一经发布不重用其含义。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "HT-1001", "请求字段校验失败", false),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "HT-1002", "请求格式无效", false),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "HT-2001", "未登录或会话已失效", false),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "HT-2002", "账号或密码不匹配", false),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "HT-2004", "当前操作不被允许", false),
    /** 状态冲突类的“当前操作禁止”，如邮箱不可用于开通、邮箱已验证。 */
    OPERATION_NOT_ALLOWED(HttpStatus.CONFLICT, "HT-2004", "当前操作不被允许", false),
    CSRF_FAILED(HttpStatus.FORBIDDEN, "HT-2005", "安全校验失败，请刷新页面后重试", false),
    /** 邀请码、验证/重置令牌无效或过期：统一说明，不透露具体原因（docs/08）。 */
    INVALID_CREDENTIAL(HttpStatus.BAD_REQUEST, "HT-2006", "链接或凭据无效或已过期", false),

    /** 档案不存在或当前账号无有效授权：统一 404，不泄露档案是否存在。 */
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "HT-3001", "档案不存在", false),

    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "HT-5001", "尝试次数过多，请稍后再试", true),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "HT-9001", "内部处理失败，请稍后重试", true);

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;
    private final boolean retryable;

    ErrorCode(HttpStatus status, String code, String defaultMessage, boolean retryable) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public boolean retryable() {
        return retryable;
    }
}
