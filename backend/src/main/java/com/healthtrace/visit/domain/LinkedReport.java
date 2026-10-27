package com.healthtrace.visit.domain;

import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.ReportType;

import java.time.LocalDate;

/** 就诊关联报告投影（visit_report JOIN report，仅 visibility=NORMAL 的报告）。 */
public class LinkedReport {

    private Long visitId;
    private Long reportId;
    private String title;
    private ReportType reportType;
    private LocalDate examinationDate;
    private ProcessingStatus processingStatus;

    public Long getVisitId() {
        return visitId;
    }

    public void setVisitId(Long visitId) {
        this.visitId = visitId;
    }

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ReportType getReportType() {
        return reportType;
    }

    public void setReportType(ReportType reportType) {
        this.reportType = reportType;
    }

    public LocalDate getExaminationDate() {
        return examinationDate;
    }

    public void setExaminationDate(LocalDate examinationDate) {
        this.examinationDate = examinationDate;
    }

    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(ProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }
}
