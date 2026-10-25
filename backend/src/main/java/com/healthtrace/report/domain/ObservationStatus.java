package com.healthtrace.report.domain;

/** OBSERVATION.status：草稿/正式/删除中。只有 FORMAL 进入趋势图表（FR-007）。 */
public enum ObservationStatus {
    DRAFT,
    FORMAL,
    DELETING
}
