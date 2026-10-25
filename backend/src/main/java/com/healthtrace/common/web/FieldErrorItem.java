package com.healthtrace.common.web;

/**
 * 字段级错误。只含字段名、原因码与说明，不回显输入值（docs/08）。
 */
public record FieldErrorItem(String field, String reasonCode, String message) {
}
