package com.healthtrace.indicator.service;

import com.healthtrace.report.domain.AbnormalFlag;
import com.healthtrace.report.domain.ResultKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 趋势接口的响应结构（FR-010/011）。 */
public final class TrendViews {

    private TrendViews() {
    }

    /**
     * 有正式数据的指标。groupCount > 1 表示存在不同单位/标本/方法的结果。
     * standard=false 表示暂未纳入标准字典、按报告原名汇总的项目（code 以 RAW: 开头）。
     */
    public record MetricSummary(String code, String standardName, String standardUnit, int count,
                                LocalDate latestDate, int groupCount, boolean standard) {
    }

    public record MetricInfo(String code, String standardName, String standardUnit, String comparabilityNote) {
    }

    public record DateRange(LocalDate from, LocalDate to) {
    }

    /**
     * 与同组最近此前可比点的增减。percent 在前值为 0 时为空；
     * note：PREVIOUS_ZERO（前值为 0）、SAME_DAY_AMBIGUOUS（同日多条结果，不计算）。
     */
    public record Change(BigDecimal absolute, BigDecimal percent, String note) {
    }

    /** 精确数值点；change 为空表示组内没有此前可比点。 */
    public record TrendPoint(long observationId, long reportId, LocalDate examinationDate, String originalValue,
                             BigDecimal numericValue, String referenceText, BigDecimal referenceLow,
                             BigDecimal referenceHigh, AbnormalFlag abnormalFlag, Change change) {
    }

    /** 非精确结果（小于/大于/定性/未知）：只进列表，不画线、不参与增减。 */
    public record NonNumericPoint(long observationId, long reportId, LocalDate examinationDate,
                                  String originalValue, ResultKind resultKind, String referenceText,
                                  AbnormalFlag abnormalFlag) {
    }

    /** 可比组：单位写法归一 + 标本 + 方法一致；组间不连线、不换算、不计算。 */
    public record TrendGroup(String groupKey, String unitText, String specimenText, String methodText,
                             boolean matchesStandardUnit, List<TrendPoint> points,
                             List<NonNumericPoint> nonNumeric) {
    }

    public record TrendView(MetricInfo metric, DateRange range, List<TrendGroup> groups, boolean unitWarning) {
    }

    /** 关注卡片用的最近一次精确数值结果；unitText 为该条结果自身的单位写法。 */
    public record LatestObservation(long observationId, long reportId, LocalDate examinationDate,
                                    String originalValue, BigDecimal numericValue, String unitText,
                                    AbnormalFlag abnormalFlag) {
    }

    /**
     * 单个指标的最新值摘要。latest 为空表示截至今天没有精确数值结果；
     * change 与趋势接口中该点的 change 语义一致（同组最近此前日期的可比点），为空表示没有此前可比点。
     */
    public record MetricLatestSummary(String metricCode, String standardName, String standardUnit,
                                      LatestObservation latest, Change change) {
    }

    /** items 顺序与请求 codes（去重后）一致。 */
    public record MetricLatestSummaries(List<MetricLatestSummary> items) {
    }
}
