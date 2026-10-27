package com.healthtrace.indicator.service;

import java.time.LocalDate;

/** 趋势时间区间：截止日为当天（含），起始日按区间往前推，同样包含在内。 */
public enum TrendRange {
    D7("7d"),
    D30("30d"),
    Y1("1y"),
    Y5("5y"),
    CUSTOM("custom");

    /** 自定义区间最长 20 年。 */
    public static final int CUSTOM_MAX_YEARS = 20;

    private final String value;

    TrendRange(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** 未知取值返回 null，由调用方生成字段错误。 */
    public static TrendRange parse(String value) {
        if (value == null || value.isBlank()) {
            return D30;
        }
        for (TrendRange r : values()) {
            if (r.value.equalsIgnoreCase(value.trim())) {
                return r;
            }
        }
        return null;
    }

    /** 预设区间的起始日（CUSTOM 不适用）。 */
    public LocalDate fromFor(LocalDate to) {
        return switch (this) {
            case D7 -> to.minusDays(6);
            case D30 -> to.minusDays(29);
            case Y1 -> to.minusYears(1).plusDays(1);
            case Y5 -> to.minusYears(5).plusDays(1);
            case CUSTOM -> throw new IllegalStateException("custom range has no implicit start");
        };
    }
}
