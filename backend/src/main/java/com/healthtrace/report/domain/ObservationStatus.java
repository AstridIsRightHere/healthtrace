package com.healthtrace.report.domain;

/**
 * OBSERVATION.status：草稿/正式/删除中/已被替代/已撤回。只有 FORMAL 进入趋势图表（FR-007）。
 * SUPERSEDED、RETRACTED 由 FR-014 修改产生，仅作审计留痕，不参与任何统计。
 */
public enum ObservationStatus {
    DRAFT,
    FORMAL,
    DELETING,
    SUPERSEDED,
    RETRACTED
}
