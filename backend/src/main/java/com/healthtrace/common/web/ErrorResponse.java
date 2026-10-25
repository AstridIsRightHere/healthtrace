package com.healthtrace.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * 统一错误响应结构（docs/08）：code、message、traceId、fieldErrors（非字段错误省略）、retryable。
 */
public record ErrorResponse(
        String code,
        String message,
        String traceId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldErrorItem> fieldErrors,
        boolean retryable) {

    public static ErrorResponse of(ErrorCode errorCode, String message, List<FieldErrorItem> fieldErrors) {
        return new ErrorResponse(errorCode.code(), message, newTraceId(), fieldErrors, errorCode.retryable());
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.defaultMessage(), List.of());
    }

    /** 不透明关联号：随机生成，不含资源标识或个人信息。 */
    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
