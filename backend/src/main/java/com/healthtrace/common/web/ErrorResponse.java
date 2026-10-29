package com.healthtrace.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.slf4j.MDC;

import java.util.List;

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

    /** 不透明关联号：优先取 TraceIdFilter 写入 MDC 的本次请求 id，缺失时（如非请求线程）随机生成。 */
    private static String newTraceId() {
        String current = MDC.get(TraceIdFilter.MDC_KEY);
        return current != null && !current.isBlank() ? current : TraceIdFilter.newTraceId();
    }
}
