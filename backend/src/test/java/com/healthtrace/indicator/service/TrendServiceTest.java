package com.healthtrace.indicator.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.indicator.domain.MetricAggregate;
import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.domain.TrendObservation;
import com.healthtrace.indicator.mapper.TrendMapper;
import com.healthtrace.indicator.service.TrendViews.DateRange;
import com.healthtrace.indicator.service.TrendViews.MetricLatestSummaries;
import com.healthtrace.indicator.service.TrendViews.MetricLatestSummary;
import com.healthtrace.indicator.service.TrendViews.MetricSummary;
import com.healthtrace.indicator.service.TrendViews.TrendGroup;
import com.healthtrace.indicator.service.TrendViews.TrendPoint;
import com.healthtrace.indicator.service.TrendViews.TrendView;
import com.healthtrace.profile.domain.ProfileRecord;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.AbnormalFlag;
import com.healthtrace.report.domain.ResultKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 趋势服务：时间区间过滤、单位分组、增减计算、越权 404。 */
class TrendServiceTest {

    /** 2026-10-25T17:00Z 已是中国时间 10-26 凌晨，用来验证截止日按中国时间计算。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T17:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY_CN = LocalDate.of(2026, 10, 26);

    private TrendMapper trendMapper;
    private ProfileMapper profileMapper;
    private TrendService service;
    private long nextId;

    @BeforeEach
    void setUp() {
        trendMapper = mock(TrendMapper.class);
        profileMapper = mock(ProfileMapper.class);
        service = new TrendService(trendMapper, profileMapper, CLOCK);
        nextId = 1;
    }

    private TrendObservation exact(String date, String value, String unit) {
        TrendObservation o = new TrendObservation();
        o.setObservationId(nextId++);
        o.setReportId(100L);
        o.setMetricCode("WBC");
        o.setExaminationDate(LocalDate.parse(date));
        o.setOriginalValue(value);
        o.setResultKind(ResultKind.EXACT);
        o.setNumericValue(new BigDecimal(value));
        o.setUnitText(unit);
        o.setReferenceText("3.5-9.5");
        o.setReferenceLow(new BigDecimal("3.50000000"));
        o.setReferenceHigh(new BigDecimal("9.50000000"));
        o.setAbnormalFlag(AbnormalFlag.NORMAL);
        return o;
    }

    private static MetricDefinition wbc() {
        MetricDefinition d = new MetricDefinition();
        d.setId(1L);
        d.setCode("WBC");
        d.setStandardName("白细胞计数");
        d.setStandardUnit("10^9/L");
        return d;
    }

    private static DateRange range(String from, String to) {
        return new DateRange(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Nested
    class Ranges {

        @Test
        void presetRangesEndOnChinaToday() {
            assertThat(service.resolveRange("7d", null, null)).isEqualTo(new DateRange(TODAY_CN.minusDays(6), TODAY_CN));
            assertThat(service.resolveRange("30d", null, null)).isEqualTo(new DateRange(TODAY_CN.minusDays(29), TODAY_CN));
            assertThat(service.resolveRange(null, null, null)).isEqualTo(new DateRange(TODAY_CN.minusDays(29), TODAY_CN));
            assertThat(service.resolveRange("1y", null, null))
                    .isEqualTo(new DateRange(TODAY_CN.minusYears(1).plusDays(1), TODAY_CN));
            assertThat(service.resolveRange("5y", null, null))
                    .isEqualTo(new DateRange(TODAY_CN.minusYears(5).plusDays(1), TODAY_CN));
        }

        @Test
        void unknownRangeRejected() {
            assertThatThrownBy(() -> service.resolveRange("90d", null, null))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        void customRangeValidated() {
            assertThat(service.resolveRange("custom", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1)))
                    .isEqualTo(range("2020-01-01", "2021-01-01"));
            assertThatThrownBy(() -> service.resolveRange("custom", null, LocalDate.of(2021, 1, 1)))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.resolveRange("custom", LocalDate.of(2022, 1, 1), LocalDate.of(2021, 1, 1)))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.resolveRange("custom", LocalDate.of(2000, 1, 1), LocalDate.of(2021, 1, 2)))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    class Grouping {

        @Test
        void onlyPointsInsideWindowReturnedButChangeUsesEarlierHistory() {
            List<TrendObservation> rows = List.of(
                    exact("2026-08-01", "5.0", "10^9/L"),
                    exact("2026-10-01", "6.0", "10^9/L"),
                    exact("2026-10-20", "4.5", "10^9/L"));

            List<TrendGroup> groups = TrendService.buildGroups(rows, "10^9/L", range("2026-09-27", "2026-10-26"));

            assertThat(groups).hasSize(1);
            List<TrendPoint> points = groups.get(0).points();
            assertThat(points).extracting(TrendPoint::examinationDate)
                    .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20));
            assertThat(points.get(0).change().absolute()).isEqualByComparingTo("1.0");
            assertThat(points.get(0).change().percent()).isEqualByComparingTo("20.00");
            assertThat(points.get(1).change().absolute()).isEqualByComparingTo("-1.5");
            assertThat(points.get(1).change().percent()).isEqualByComparingTo("-25.00");
            assertThat(groups.get(0).matchesStandardUnit()).isTrue();
        }

        @Test
        void groupWithoutPointsInWindowIsOmitted() {
            List<TrendObservation> rows = List.of(exact("2025-01-01", "5.0", "10^9/L"));
            assertThat(TrendService.buildGroups(rows, "10^9/L", range("2026-09-27", "2026-10-26"))).isEmpty();
        }

        @Test
        void unitNotationIsNormalizedButDifferentUnitsAreSeparateGroups() {
            List<TrendObservation> rows = List.of(
                    exact("2026-10-01", "6.0", "×10⁹/L"),
                    exact("2026-10-05", "6.5", "10^9/L"),
                    exact("2026-10-10", "6500", "/uL"));

            List<TrendGroup> groups = TrendService.buildGroups(rows, "10^9/L", range("2026-09-27", "2026-10-26"));

            assertThat(groups).hasSize(2);
            TrendGroup standard = groups.get(0);
            assertThat(standard.points()).hasSize(2);
            assertThat(standard.matchesStandardUnit()).isTrue();
            TrendGroup other = groups.get(1);
            assertThat(other.unitText()).isEqualTo("/uL");
            assertThat(other.matchesStandardUnit()).isFalse();
            // 组间不计算增减：/uL 组第一个点没有前值
            assertThat(other.points().get(0).change()).isNull();
            assertThat(other.points().get(0).numericValue()).isEqualByComparingTo("6500");
        }

        @Test
        void differentSpecimenIsDifferentGroup() {
            TrendObservation blood = exact("2026-10-01", "6.0", "10^9/L");
            TrendObservation other = exact("2026-10-02", "6.0", "10^9/L");
            other.setSpecimenText("胸水");
            assertThat(TrendService.buildGroups(List.of(blood, other), "10^9/L", range("2026-09-27", "2026-10-26")))
                    .hasSize(2);
        }

        @Test
        void previousZeroGivesNoPercent() {
            List<TrendObservation> rows = List.of(
                    exact("2026-10-01", "0", "mg/L"),
                    exact("2026-10-05", "2.5", "mg/L"));
            TrendPoint p = TrendService.buildGroups(rows, "mg/L", range("2026-09-27", "2026-10-26"))
                    .get(0).points().get(1);
            assertThat(p.change().absolute()).isEqualByComparingTo("2.5");
            assertThat(p.change().percent()).isNull();
            assertThat(p.change().note()).isEqualTo(TrendService.NOTE_PREVIOUS_ZERO);
        }

        @Test
        void sameDayResultsAreAmbiguous() {
            List<TrendObservation> rows = List.of(
                    exact("2026-10-01", "6.0", "10^9/L"),
                    exact("2026-10-05", "6.5", "10^9/L"),
                    exact("2026-10-05", "7.0", "10^9/L"),
                    exact("2026-10-09", "7.5", "10^9/L"));
            List<TrendPoint> points = TrendService.buildGroups(rows, "10^9/L", range("2026-09-27", "2026-10-26"))
                    .get(0).points();
            assertThat(points).hasSize(4);
            assertThat(points.get(1).change().note()).isEqualTo(TrendService.NOTE_SAME_DAY);
            assertThat(points.get(2).change().note()).isEqualTo(TrendService.NOTE_SAME_DAY);
            // 前一日期有多条：同样不计算
            assertThat(points.get(3).change().note()).isEqualTo(TrendService.NOTE_SAME_DAY);
            assertThat(points.get(3).change().absolute()).isNull();
        }

        @Test
        void nonExactResultsOnlyGoToList() {
            TrendObservation less = exact("2026-10-03", "0", "mg/L");
            less.setOriginalValue("<0.5");
            less.setResultKind(ResultKind.LESS_THAN);
            less.setNumericValue(new BigDecimal("0.5"));
            List<TrendObservation> rows = List.of(exact("2026-10-01", "1.2", "mg/L"), less,
                    exact("2026-10-05", "2.4", "mg/L"));

            TrendGroup g = TrendService.buildGroups(rows, "mg/L", range("2026-09-27", "2026-10-26")).get(0);

            assertThat(g.points()).hasSize(2);
            assertThat(g.nonNumeric()).singleElement()
                    .satisfies(n -> assertThat(n.originalValue()).isEqualTo("<0.5"));
            // 增减跳过非精确值，与最近的精确点比较
            assertThat(g.points().get(1).change().absolute()).isEqualByComparingTo("1.2");
        }

        @Test
        void trailingZerosAreStripped() {
            TrendObservation o = exact("2026-10-01", "120.00000000", "g/L");
            TrendPoint p = TrendService.buildGroups(List.of(o), "g/L", range("2026-09-27", "2026-10-26"))
                    .get(0).points().get(0);
            assertThat(p.numericValue().toPlainString()).isEqualTo("120");
            assertThat(p.referenceLow().toPlainString()).isEqualTo("3.5");
        }
    }

    @Nested
    class Access {

        @Test
        void otherProfileIsNotFoundForTrend() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 99L)).willReturn(null);
            assertThatThrownBy(() -> service.getTrend(7L, 99L, "WBC", "30d", null, null))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND));
            verify(trendMapper, never()).findFormalByMetric(anyLong(), anyLong(), any(), any());
            verify(trendMapper, never()).findFormalHistoryByMetric(anyLong(), anyLong(), any());
        }

        @Test
        void otherProfileIsNotFoundForMetricList() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 99L)).willReturn(null);
            assertThatThrownBy(() -> service.listMetrics(7L, 99L))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND));
            verify(trendMapper, never()).aggregateFormalByProfile(anyLong());
        }

        @Test
        void unknownMetricRejected() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 5L)).willReturn(new ProfileRecord());
            given(trendMapper.findDefinitionByCode("BUN")).willReturn(null);
            assertThatThrownBy(() -> service.getTrend(7L, 5L, "BUN", "30d", null, null))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        void trendQueriesUpToRangeEndAndFlagsUnitWarning() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 5L)).willReturn(new ProfileRecord());
            given(trendMapper.findDefinitionByCode("WBC")).willReturn(wbc());
            given(trendMapper.findFormalByMetric(5L, 1L, TODAY_CN.minusDays(29), TODAY_CN)).willReturn(List.of(
                    exact("2026-10-01", "6.0", "10^9/L"),
                    exact("2026-10-10", "6500", "/uL")));

            TrendView view = service.getTrend(7L, 5L, "WBC", "30d", null, null);

            assertThat(view.metric().standardName()).isEqualTo("白细胞计数");
            assertThat(view.groups()).hasSize(2);
            assertThat(view.unitWarning()).isTrue();
        }

        @Test
        void metricListCountsGroups() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 5L)).willReturn(new ProfileRecord());
            given(trendMapper.findAllDefinitions()).willReturn(List.of(wbc()));
            given(trendMapper.aggregateFormalByProfile(5L)).willReturn(List.of(
                    aggregate("WBC", null, "10^9/L", 2, "2026-10-01", 1, 2),
                    // 原始写法不同（多一个空格）但归一后同组：合并计数，不增加组数
                    aggregate("WBC", null, "10^9/L ", 1, "2026-10-05", 3, 3),
                    aggregate("WBC", null, "/uL", 1, "2026-10-10", 4, 4)));

            List<MetricSummary> metrics = service.listMetrics(7L, 5L);

            assertThat(metrics).singleElement().satisfies(m -> {
                assertThat(m.code()).isEqualTo("WBC");
                assertThat(m.count()).isEqualTo(4);
                assertThat(m.groupCount()).isEqualTo(2);
                assertThat(m.latestDate()).isEqualTo(LocalDate.of(2026, 10, 10));
                assertThat(m.standard()).isTrue();
            });
        }

        private TrendObservation raw(String date, String name, String value, String unit) {
            TrendObservation o = exact(date, value, unit);
            o.setMetricCode(null);
            o.setOriginalName(name);
            return o;
        }

        private MetricAggregate aggregate(String code, String name, String unit, long count, String latest,
                                          long firstPos, long lastPos) {
            MetricAggregate a = new MetricAggregate();
            a.setMetricCode(code);
            a.setOriginalName(name);
            a.setUnitText(unit);
            a.setObservationCount(count);
            a.setLatestDate(LocalDate.parse(latest));
            a.setFirstPos(firstPos);
            a.setLastPos(lastPos);
            return a;
        }

        @Test
        void unmappedObservationsAreListedByOriginalName() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 5L)).willReturn(new ProfileRecord());
            given(trendMapper.findAllDefinitions()).willReturn(List.of(wbc()));
            given(trendMapper.aggregateFormalByProfile(5L)).willReturn(List.of(
                    aggregate("WBC", null, "10^9/L", 1, "2026-10-01", 1, 1),
                    aggregate(null, "★促甲状腺激素", "uIU/mL", 1, "2026-09-10", 2, 2),
                    aggregate(null, "促甲状腺激素 ", "mIU/L", 1, "2026-10-10", 3, 3)));

            List<MetricSummary> metrics = service.listMetrics(7L, 5L);

            assertThat(metrics).hasSize(2);
            assertThat(metrics.get(1)).satisfies(m -> {
                assertThat(m.code()).isEqualTo("RAW:促甲状腺激素");
                assertThat(m.standardName()).isEqualTo("促甲状腺激素");
                // 展示单位取原排序最后一行的写法
                assertThat(m.standardUnit()).isEqualTo("mIU/L");
                assertThat(m.count()).isEqualTo(2);
                assertThat(m.groupCount()).isEqualTo(2);
                assertThat(m.standard()).isFalse();
            });
        }

        @Test
        void metricWithoutDefinitionIsSkipped() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 5L)).willReturn(new ProfileRecord());
            given(trendMapper.findAllDefinitions()).willReturn(List.of(wbc()));
            given(trendMapper.aggregateFormalByProfile(5L)).willReturn(List.of(
                    aggregate("HGB", null, "g/L", 1, "2026-10-01", 1, 1)));

            assertThat(service.listMetrics(7L, 5L)).isEmpty();
        }

        @Test
        void rawTrendOnlyIncludesSameNormalizedName() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 5L)).willReturn(new ProfileRecord());
            given(trendMapper.findFormalUnmapped(5L, TODAY_CN)).willReturn(List.of(
                    raw("2026-10-20", "★促甲状腺激素", "2.1", "uIU/mL"),
                    raw("2026-10-21", "游离甲状腺素", "1.2", "ng/dL")));

            TrendView view = service.getTrend(7L, 5L, "RAW:促甲状腺激素", "30d", null, null);

            assertThat(view.metric().standardName()).isEqualTo("促甲状腺激素");
            assertThat(view.groups()).singleElement()
                    .satisfies(g -> assertThat(g.points()).hasSize(1));
            verify(trendMapper, never()).findDefinitionByCode(any());
        }

        @Test
        void otherProfileRawTrendIsNotFound() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 99L)).willReturn(null);
            assertThatThrownBy(() -> service.getTrend(7L, 99L, "RAW:X", "30d", null, null))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND));
            verify(trendMapper, never()).findFormalUnmapped(anyLong(), any());
        }

        @Test
        void trendChangeOfFirstPointInWindowUsesHistoryQuery() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 5L)).willReturn(new ProfileRecord());
            given(trendMapper.findDefinitionByCode("WBC")).willReturn(wbc());
            LocalDate from = TODAY_CN.minusDays(29);
            given(trendMapper.findFormalHistoryByMetric(5L, 1L, from)).willReturn(List.of(
                    exact("2026-08-01", "5.0", "10^9/L")));
            given(trendMapper.findFormalByMetric(5L, 1L, from, TODAY_CN)).willReturn(List.of(
                    exact("2026-10-01", "6.0", "10^9/L")));

            TrendView view = service.getTrend(7L, 5L, "WBC", "30d", null, null);

            // 窗口前的历史点不返回，但参与窗口首点的增减计算
            assertThat(view.groups()).singleElement().satisfies(g -> {
                assertThat(g.points()).singleElement().satisfies(p -> {
                    assertThat(p.examinationDate()).isEqualTo(LocalDate.of(2026, 10, 1));
                    assertThat(p.change().absolute()).isEqualByComparingTo("1.0");
                    assertThat(p.change().percent()).isEqualByComparingTo("20.00");
                });
            });
        }
    }

    @Nested
    class LatestSummaries {

        private MetricDefinition hgb() {
            MetricDefinition d = new MetricDefinition();
            d.setId(2L);
            d.setCode("HGB");
            d.setStandardName("血红蛋白");
            d.setStandardUnit("g/L");
            return d;
        }

        @BeforeEach
        void allowAccess() {
            given(profileMapper.findActiveByAccountAndProfile(7L, 5L)).willReturn(new ProfileRecord());
            given(trendMapper.findAllDefinitions()).willReturn(List.of(wbc(), hgb()));
        }

        @Test
        void newestPointAcrossGroupsWithItsChange() {
            given(trendMapper.findRecentExactByMetrics(5L, List.of(1L), TODAY_CN)).willReturn(List.of(
                    exact("2026-10-01", "6.0", "10^9/L"),
                    exact("2026-10-03", "6500", "/uL"),
                    exact("2026-10-05", "7.5", "×10⁹/L")));

            MetricLatestSummaries result = service.getLatestSummaries(7L, 5L, "WBC");

            assertThat(result.items()).singleElement().satisfies(item -> {
                assertThat(item.metricCode()).isEqualTo("WBC");
                assertThat(item.standardName()).isEqualTo("白细胞计数");
                assertThat(item.standardUnit()).isEqualTo("10^9/L");
                assertThat(item.latest().examinationDate()).isEqualTo(LocalDate.of(2026, 10, 5));
                assertThat(item.latest().observationId()).isEqualTo(3L);
                assertThat(item.latest().reportId()).isEqualTo(100L);
                assertThat(item.latest().originalValue()).isEqualTo("7.5");
                assertThat(item.latest().numericValue()).isEqualByComparingTo("7.5");
                assertThat(item.latest().unitText()).isEqualTo("×10⁹/L");
                assertThat(item.latest().abnormalFlag()).isEqualTo(AbnormalFlag.NORMAL);
                // 与同组（归一后同单位）10-01 的 6.0 比较，不与 /uL 组比较
                assertThat(item.change().absolute()).isEqualByComparingTo("1.5");
                assertThat(item.change().percent()).isEqualByComparingTo("25.00");
            });
        }

        @Test
        void itemsFollowRequestOrderAndMissingDataIsNull() {
            given(trendMapper.findRecentExactByMetrics(5L, List.of(2L, 1L), TODAY_CN)).willReturn(List.of(
                    exact("2026-10-01", "6.0", "10^9/L")));

            MetricLatestSummaries result = service.getLatestSummaries(7L, 5L, " HGB, WBC,HGB,");

            assertThat(result.items()).extracting(MetricLatestSummary::metricCode).containsExactly("HGB", "WBC");
            assertThat(result.items().get(0).latest()).isNull();
            assertThat(result.items().get(0).change()).isNull();
            assertThat(result.items().get(1).latest().numericValue()).isEqualByComparingTo("6.0");
            assertThat(result.items().get(1).change()).isNull();
        }

        @Test
        void sameDayTieTakesLargerObservationIdAndIsAmbiguous() {
            given(trendMapper.findRecentExactByMetrics(5L, List.of(1L), TODAY_CN)).willReturn(List.of(
                    exact("2026-10-01", "6.0", "10^9/L"),
                    exact("2026-10-05", "7.0", "10^9/L"),
                    exact("2026-10-05", "7.2", "10^9/L")));

            MetricLatestSummary item = service.getLatestSummaries(7L, 5L, "WBC").items().get(0);

            assertThat(item.latest().observationId()).isEqualTo(3L);
            assertThat(item.change().note()).isEqualTo("SAME_DAY_AMBIGUOUS");
            assertThat(item.change().absolute()).isNull();
        }

        @Test
        void blankCodesRejected() {
            assertValidation(" , ", "NotBlank");
            assertValidation(null, "NotBlank");
        }

        @Test
        void moreThanTwentyCodesRejected() {
            String codes = String.join(",", java.util.stream.IntStream.rangeClosed(1, 21)
                    .mapToObj(i -> "C" + i).toList());
            assertValidation(codes, "Size");
        }

        @Test
        void unknownCodeRejected() {
            assertValidation("WBC,BUN", "UnknownMetric");
            verify(trendMapper, never()).findRecentExactByMetrics(anyLong(), any(), any());
        }

        @Test
        void otherProfileIsNotFound() {
            assertThatThrownBy(() -> service.getLatestSummaries(7L, 99L, "WBC"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND));
            verify(trendMapper, never()).findRecentExactByMetrics(anyLong(), any(), any());
        }

        private void assertValidation(String codes, String reason) {
            assertThatThrownBy(() -> service.getLatestSummaries(7L, 5L, codes))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.getFieldErrors()).singleElement().satisfies(f -> {
                            assertThat(f.field()).isEqualTo("codes");
                            assertThat(f.reasonCode()).isEqualTo(reason);
                        });
                    });
        }
    }
}
