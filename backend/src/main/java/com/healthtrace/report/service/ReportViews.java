package com.healthtrace.report.service;

import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.ReportType;

import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 报告模块对外视图。不包含对象键、内容摘要等内部字段。 */
public final class ReportViews {

    private ReportViews() {
    }

    public record ReportView(
            long id,
            long profileId,
            String title,
            ReportType reportType,
            LocalDate examinationDate,
            ProcessingStatus processingStatus,
            LocalDateTime createdAt) {
    }

    public record PhotoView(
            long id,
            long reportId,
            int pageOrder,
            String mediaType,
            long byteSize,
            boolean hasPreview) {
    }

    /** 上传结果：UPLOADED 已保存；DUPLICATE_SUSPECTED 命中重复、未保存，由用户决定是否以 KEEP 重传。 */
    public enum UploadOutcome {
        UPLOADED,
        DUPLICATE_SUSPECTED
    }

    public record PhotoUploadResult(
            UploadOutcome result,
            PhotoView photo,
            List<Long> existingReportIds) {

        static PhotoUploadResult uploaded(PhotoView photo) {
            return new PhotoUploadResult(UploadOutcome.UPLOADED, photo, List.of());
        }

        static PhotoUploadResult duplicate(List<Long> existingReportIds) {
            return new PhotoUploadResult(UploadOutcome.DUPLICATE_SUSPECTED, null, List.copyOf(existingReportIds));
        }
    }

    public record ReportListItem(
            long id,
            String title,
            ReportType reportType,
            LocalDate examinationDate,
            ProcessingStatus processingStatus,
            LocalDateTime createdAt,
            int photoCount,
            Long coverPhotoId,
            boolean coverHasPreview,
            String lastOcrErrorCode) {
    }

    /** 已确认报告按种类聚合的一组（FR-010 报告对比入口）。reports 按检查日期、id 倒序。 */
    public record ReportGroup(
            String key,
            String label,
            int count,
            LocalDate latestDate,
            List<ReportListItem> reports) {
    }

    public enum PhotoVariant {
        PREVIEW,
        ORIGINAL
    }

    /**
     * 图片读取结果：redirect 非空时跳转到短时签名地址；否则由应用直接回传 content。
     */
    public record PhotoContent(
            URI redirect,
            InputStream content,
            String mediaType,
            long byteSize) {
    }
}
