package com.healthtrace.report.service;

import com.healthtrace.report.service.ReviewViews.ObservationInput;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 已确认报告修改（FR-014）的请求与响应。 */
public final class RevisionViews {

    private RevisionViews() {
    }

    public record ReviseCommand(
            Long version,
            LocalDate examinationDate,
            String reason,
            List<ObservationInput> observations) {
    }

    public record ReviseResult(
            long reportId,
            long version,
            LocalDate examinationDate,
            long revisionId,
            int changedCount) {
    }

    public enum ChangeType {
        ADDED,
        UPDATED,
        REMOVED
    }

    /** 修改记录中某一项指标的快照；只含用户可见的原始字段。 */
    public record ObservationSnapshot(
            String originalName,
            String originalValue,
            String unitText,
            String referenceText) {
    }

    public record RevisionChange(
            ChangeType type,
            ObservationSnapshot before,
            ObservationSnapshot after) {
    }

    public record DateChange(String before, String after) {
    }

    /** 加密存储的差异正文（change_payload 明文结构）。 */
    public record RevisionPayload(DateChange examinationDate, List<RevisionChange> changes) {
    }

    public record RevisionView(
            long id,
            long fromVersion,
            long toVersion,
            String reason,
            LocalDateTime createdAt,
            DateChange examinationDate,
            List<RevisionChange> changes) {
    }
}
