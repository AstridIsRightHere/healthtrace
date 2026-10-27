package com.healthtrace.visit.service;

import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.ReportType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 就诊时间线接口视图（FR-013）。 */
public final class VisitViews {

    private VisitViews() {
    }

    public record LinkedReportView(
            long id,
            String title,
            ReportType reportType,
            LocalDate examinationDate,
            ProcessingStatus processingStatus) {
    }

    public record VisitView(
            long id,
            long profileId,
            LocalDate visitDate,
            String hospitalText,
            String department,
            String note,
            long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<LinkedReportView> reports) {
    }

    /** 新建/编辑共用的可编辑字段；编辑时 version 必填用于乐观锁。 */
    public record VisitInput(
            LocalDate visitDate,
            String hospitalText,
            String department,
            String note) {
    }
}
