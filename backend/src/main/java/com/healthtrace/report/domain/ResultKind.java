package com.healthtrace.report.domain;

/** OBSERVATION.result_kind：精确数值/小于/大于/定性/未知。 */
public enum ResultKind {
    EXACT,
    LESS_THAN,
    GREATER_THAN,
    QUALITATIVE,
    UNKNOWN
}
