package com.healthtrace.report.domain;

/** OBSERVATION.abnormal_flag：仅依据报告单参考范围机械比较，不做医学判断。 */
public enum AbnormalFlag {
    HIGH,
    LOW,
    NORMAL,
    UNKNOWN
}
