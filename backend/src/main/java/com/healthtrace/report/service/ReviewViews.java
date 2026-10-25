package com.healthtrace.report.service;

import com.healthtrace.report.domain.AbnormalFlag;
import com.healthtrace.report.domain.OcrJob;
import com.healthtrace.report.domain.OcrJobStatus;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.ResultKind;
import com.healthtrace.report.service.ReportViews.PhotoView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** OCR 任务、草稿与确认的对外视图与命令。 */
public final class ReviewViews {

    private ReviewViews() {
    }

    public record OcrJobView(
            long id,
            long reportId,
            OcrJobStatus status,
            int attemptCount,
            int maxAttempts,
            String errorCode,
            LocalDateTime nextRetryAt,
            LocalDateTime createdAt,
            LocalDateTime finishedAt) {

        static OcrJobView of(OcrJob j) {
            return new OcrJobView(j.getId(), j.getReportId(), j.getStatus(), j.getAttemptCount(),
                    OcrJobProcessor.MAX_ATTEMPTS, j.getErrorCode(), j.getNextRetryAt(), j.getCreatedAt(),
                    j.getFinishedAt());
        }
    }

    public record DraftReport(
            long id,
            long profileId,
            String title,
            LocalDate examinationDate,
            ProcessingStatus processingStatus,
            long version) {
    }

    /**
     * @param confidence 供应商真实置信度（0～1）；未返回或已被用户修改时为 null
     */
    public record ObservationView(
            long id,
            String originalName,
            String originalValue,
            String unitText,
            String referenceText,
            ResultKind resultKind,
            AbnormalFlag abnormalFlag,
            BigDecimal confidence,
            Long sourcePhotoId,
            int rowOrder) {
    }

    /**
     * 草稿详情。rawText 为 OCR 原始文本（仅识别成功时有值）；已确认的报告返回正式记录。
     */
    public record DraftView(
            DraftReport report,
            List<PhotoView> photos,
            OcrJobView ocrJob,
            String rawText,
            List<ObservationView> observations) {
    }

    public record ObservationInput(
            Long id,
            String originalName,
            String originalValue,
            String unitText,
            String referenceText,
            Long sourcePhotoId) {
    }

    public record ConfirmCommand(
            Long version,
            LocalDate examinationDate,
            List<ObservationInput> observations) {
    }

    public record ConfirmResult(
            long reportId,
            ProcessingStatus processingStatus,
            long version,
            LocalDate examinationDate,
            int observationCount) {
    }
}
