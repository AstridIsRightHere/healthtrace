package com.healthtrace.report.compare;

import com.healthtrace.indicator.service.TrendViews.Change;
import com.healthtrace.report.domain.AbnormalFlag;
import com.healthtrace.report.domain.ResultKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 报告对比（FR-010/011）对外视图。base 为“上次”，target 为“此次”，顺序由用户选择决定，不重排。 */
public final class ComparisonViews {

    private ComparisonViews() {
    }

    /** 行状态：可比/不可比（单位、标本或方法不同）/歧义（同报告同项目多条）/仅上次/仅此次。 */
    public enum RowStatus {
        COMPARABLE,
        NOT_COMPARABLE,
        AMBIGUOUS,
        BASE_ONLY,
        TARGET_ONLY
    }

    public record ReportBrief(long id, String title, LocalDate examinationDate) {
    }

    public record ObservationCell(long observationId, String originalValue, BigDecimal numericValue,
                                  ResultKind resultKind, String unitText, String specimenText, String methodText,
                                  String referenceText, AbnormalFlag abnormalFlag) {
    }

    /** change 仅在 COMPARABLE 且两侧均为精确数值时给出，规则与趋势增减一致。 */
    public record ComparisonRow(String key, String name, boolean standard, RowStatus status,
                                ObservationCell base, ObservationCell target, Change change) {
    }

    public record ComparisonView(ReportBrief base, ReportBrief target, List<ComparisonRow> rows) {
    }
}
