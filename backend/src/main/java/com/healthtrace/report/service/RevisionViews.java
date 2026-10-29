package com.healthtrace.report.service;

import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.service.ReviewViews.ObservationInput;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 已确认报告修改（FR-014）的请求与响应。 */
public final class RevisionViews {

    private RevisionViews() {
    }

    /** 单项操作类型：新增 / 修改 / 删除一条正式结果。 */
    public enum OperationType {
        ADD,
        UPDATE,
        DELETE
    }

    /**
     * 一项修改操作。
     * <ul>
     *   <li>ADD：只需 observation（其 id 字段忽略）。</li>
     *   <li>UPDATE：observationId 为当前正式结果行 id，observation 为修改后的完整字段。</li>
     *   <li>DELETE：只需 observationId。</li>
     * </ul>
     */
    public record RevisionOperation(
            OperationType op,
            Long observationId,
            ObservationInput observation) {
    }

    /** POST /api/v1/reports/{reportId}/revisions 请求体；examinationDate 为空表示不修改检查日期。 */
    public record CreateRevisionCommand(
            Long version,
            String reason,
            LocalDate examinationDate,
            List<RevisionOperation> operations) {
    }

    public record ReviseResult(
            long reportId,
            long version,
            LocalDate examinationDate,
            long revisionId,
            int changeCount) {
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

    /**
     * 一项变更。observationId 为变更前的行（ADDED 为空），newObservationId 为变更后的新行（REMOVED 为空）。
     * 早期（V10 之前）的修改记录没有行 id，两者均为空。
     */
    public record RevisionChange(
            ChangeType type,
            Long observationId,
            Long newObservationId,
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

    /** 某一结果版本链中的一个版本（GET .../observations/{id}/history，最新在前）。 */
    public record ObservationVersionView(
            long id,
            Long supersedesId,
            ObservationStatus status,
            String originalName,
            String originalValue,
            String unitText,
            String referenceText,
            LocalDate examinationDate,
            Long sourcePhotoId,
            Long createdRevisionId,
            Long retiredRevisionId,
            LocalDateTime createdAt,
            LocalDateTime retiredAt) {
    }
}
