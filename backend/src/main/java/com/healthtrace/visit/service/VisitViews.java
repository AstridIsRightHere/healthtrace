package com.healthtrace.visit.service;

import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.ReportKind;
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
            ReportKind reportKind,
            LocalDate examinationDate,
            String conclusionText,
            ProcessingStatus processingStatus) {
    }

    /** 报告详情反查其所属就诊（任务三）：只回就诊自身字段，避免与报告列表互相嵌套。 */
    public record ReportVisitView(
            long id,
            LocalDate visitDate,
            String hospitalText,
            String department,
            String note) {
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

    /**
     * 合并时间线条目（任务三）：就诊与「未关联任何就诊的报告」统一按日期倒序排在一条轴上。
     * type=VISIT 时 visit 非空、report 为空；type=REPORT 时相反。
     * date 为排序与分组用的归一日期：就诊取 visitDate，报告取 examinationDate。
     */
    public record TimelineEntryView(
            TimelineEntryType type,
            LocalDate date,
            VisitView visit,
            LinkedReportView report) {

        static TimelineEntryView ofVisit(VisitView visit) {
            return new TimelineEntryView(TimelineEntryType.VISIT, visit.visitDate(), visit, null);
        }

        static TimelineEntryView ofReport(LinkedReportView report) {
            return new TimelineEntryView(TimelineEntryType.REPORT, report.examinationDate(), null, report);
        }
    }

    public enum TimelineEntryType {
        VISIT,
        REPORT
    }

    /** 新建/编辑共用的可编辑字段；编辑时 version 必填用于乐观锁。 */
    public record VisitInput(
            LocalDate visitDate,
            String hospitalText,
            String department,
            String note) {
    }
}
