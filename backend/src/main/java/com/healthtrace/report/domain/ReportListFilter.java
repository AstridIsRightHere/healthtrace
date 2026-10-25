package com.healthtrace.report.domain;

/**
 * 报告列表筛选（GET /patient-profiles/{profileId}/reports?status=）。
 * DRAFT 是“待处理”的筛选别名，不是表中的状态值：visibility=NORMAL 且 processing_status≠CONFIRMED。
 */
public enum ReportListFilter {
    DRAFT
}
