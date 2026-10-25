package com.healthtrace.common.web;

import java.util.List;

/**
 * 可预期的业务错误。消息面向用户，禁止包含邮箱、令牌、密码等敏感内容。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldErrorItem> fieldErrors;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public ApiException(ErrorCode errorCode, String message, List<FieldErrorItem> fieldErrors) {
        // 业务错误不需要堆栈，也避免日志意外记录上下文
        super(message, null, false, false);
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<FieldErrorItem> getFieldErrors() {
        return fieldErrors;
    }
}
