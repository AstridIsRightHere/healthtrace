package com.healthtrace.report.compare;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.domain.TrendObservation;
import com.healthtrace.indicator.mapper.TrendMapper;
import com.healthtrace.report.compare.ComparisonViews.ComparisonRow;
import com.healthtrace.report.compare.ComparisonViews.ComparisonView;
import com.healthtrace.report.compare.ComparisonViews.RowStatus;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.ResultKind;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.report.service.ReportAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 报告对比：可比/不可比/歧义/单侧、RAW 原名归一匹配、增减计算、越权与状态 404、同一报告 400。 */
class ReportComparisonServiceTest {

    private static final long ACCOUNT = 7L;
    private static final long PROFILE = 11L;
    private static final long BASE = 1L;
    private static final long TARGET = 2L;

    private final ReportAccess reportAccess = mock(ReportAccess.class);
    private final TrendMapper trendMapper = mock(TrendMapper.class);
    private ReportComparisonService service;
    private long nextId = 100;

    @BeforeEach
    void setUp() {
        service = new ReportComparisonService(reportAccess, trendMapper);
        given(reportAccess.requireOwnedReport(ACCOUNT, BASE))
                .willReturn(report(BASE, PROFILE, ProcessingStatus.CONFIRMED, LocalDate.of(2026, 7, 1)));
        given(reportAccess.requireOwnedReport(ACCOUNT, TARGET))
                .willReturn(report(TARGET, PROFILE, ProcessingStatus.CONFIRMED, LocalDate.of(2026, 10, 1)));
        given(trendMapper.findAllDefinitions()).willReturn(List.of(
                definition("WBC", "白细胞计数", 10), definition("TSH", "促甲状腺激素", 5)));
        given(trendMapper.findFormalByReport(anyLong())).willReturn(List.of());
    }

    private static Report report(long id, long profileId, ProcessingStatus status, LocalDate date) {
        Report r = new Report();
        r.setId(id);
        r.setProfileId(profileId);
        r.setTitle("报告" + id);
        r.setExaminationDate(date);
        r.setProcessingStatus(status);
        r.setVisibilityStatus(VisibilityStatus.NORMAL);
        return r;
    }

    private static MetricDefinition definition(String code, String name, int sortOrder) {
        MetricDefinition d = new MetricDefinition();
        d.setCode(code);
        d.setStandardName(name);
        d.setSortOrder(sortOrder);
        return d;
    }

    private TrendObservation obs(long reportId, String code, String originalName, String value, String unit) {
        TrendObservation o = new TrendObservation();
        o.setObservationId(nextId++);
        o.setReportId(reportId);
        o.setMetricCode(code);
        o.setOriginalName(originalName);
        o.setOriginalValue(value);
        try {
            o.setNumericValue(new BigDecimal(value));
            o.setResultKind(ResultKind.EXACT);
        } catch (NumberFormatException e) {
            o.setResultKind(ResultKind.QUALITATIVE);
        }
        o.setUnitText(unit);
        return o;
    }

    private void data(List<TrendObservation> base, List<TrendObservation> target) {
        given(trendMapper.findFormalByReport(BASE)).willReturn(base);
        given(trendMapper.findFormalByReport(TARGET)).willReturn(target);
    }

    private static ComparisonRow row(ComparisonView view, String key) {
        return view.rows().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((ApiException) t).getErrorCode();
    }

    @Test
    void comparableRowHasChange() {
        data(List.of(obs(BASE, "TSH", "TSH", "2.1", "mIU/L")),
                List.of(obs(TARGET, "TSH", "促甲状腺激素", "2.40", " mIU/L ")));

        ComparisonView view = service.compare(ACCOUNT, BASE, TARGET);

        assertThat(view.base().id()).isEqualTo(BASE);
        assertThat(view.target().examinationDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        ComparisonRow r = row(view, "TSH");
        assertThat(r.name()).isEqualTo("促甲状腺激素");
        assertThat(r.standard()).isTrue();
        assertThat(r.status()).isEqualTo(RowStatus.COMPARABLE);
        assertThat(r.target().numericValue()).isEqualByComparingTo("2.4");
        assertThat(r.change().absolute()).isEqualByComparingTo("0.3");
        assertThat(r.change().percent()).isEqualByComparingTo("14.29");
        assertThat(r.change().note()).isNull();
    }

    @Test
    void previousZeroHasNoPercent() {
        data(List.of(obs(BASE, "WBC", "白细胞", "0", "10^9/L")),
                List.of(obs(TARGET, "WBC", "白细胞", "5", "10^9/L")));

        ComparisonRow r = row(service.compare(ACCOUNT, BASE, TARGET), "WBC");

        assertThat(r.status()).isEqualTo(RowStatus.COMPARABLE);
        assertThat(r.change().absolute()).isEqualByComparingTo("5");
        assertThat(r.change().percent()).isNull();
        assertThat(r.change().note()).isEqualTo("PREVIOUS_ZERO");
    }

    @Test
    void differentUnitIsNotComparable() {
        data(List.of(obs(BASE, "WBC", "白细胞", "5", "10^9/L")),
                List.of(obs(TARGET, "WBC", "白细胞", "5000", "/uL")));

        ComparisonRow r = row(service.compare(ACCOUNT, BASE, TARGET), "WBC");

        assertThat(r.status()).isEqualTo(RowStatus.NOT_COMPARABLE);
        assertThat(r.base()).isNotNull();
        assertThat(r.target()).isNotNull();
        assertThat(r.change()).isNull();
    }

    @Test
    void comparableNonNumericHasNoChange() {
        data(List.of(obs(BASE, null, "尿蛋白", "阴性", null)),
                List.of(obs(TARGET, null, "尿蛋白", "+", null)));

        ComparisonRow r = row(service.compare(ACCOUNT, BASE, TARGET), "RAW:尿蛋白");

        assertThat(r.status()).isEqualTo(RowStatus.COMPARABLE);
        assertThat(r.change()).isNull();
    }

    @Test
    void oneSidedRows() {
        data(List.of(obs(BASE, "WBC", "白细胞", "5", "10^9/L")),
                List.of(obs(TARGET, "TSH", "TSH", "2", "mIU/L")));

        ComparisonView view = service.compare(ACCOUNT, BASE, TARGET);

        ComparisonRow wbc = row(view, "WBC");
        assertThat(wbc.status()).isEqualTo(RowStatus.BASE_ONLY);
        assertThat(wbc.target()).isNull();
        assertThat(wbc.change()).isNull();
        ComparisonRow tsh = row(view, "TSH");
        assertThat(tsh.status()).isEqualTo(RowStatus.TARGET_ONLY);
        assertThat(tsh.base()).isNull();
    }

    @Test
    void rawRowsMatchByNormalizedName() {
        data(List.of(obs(BASE, null, "★促甲状腺激素", "2", "mIU/L")),
                List.of(obs(TARGET, null, "促甲状腺激素", "3", "mIU/L")));

        ComparisonView view = service.compare(ACCOUNT, BASE, TARGET);

        assertThat(view.rows()).hasSize(1);
        ComparisonRow r = view.rows().get(0);
        assertThat(r.key()).isEqualTo("RAW:促甲状腺激素");
        assertThat(r.name()).isEqualTo("促甲状腺激素");
        assertThat(r.standard()).isFalse();
        assertThat(r.status()).isEqualTo(RowStatus.COMPARABLE);
        assertThat(r.change().absolute()).isEqualByComparingTo("1");
        assertThat(r.change().percent()).isEqualByComparingTo("50");
    }

    @Test
    void duplicateKeyIsAmbiguousAndShowsFirst() {
        TrendObservation first = obs(TARGET, "WBC", "白细胞", "6", "10^9/L");
        TrendObservation second = obs(TARGET, "WBC", "白细胞", "7", "10^9/L");
        data(List.of(obs(BASE, "WBC", "白细胞", "5", "10^9/L")), List.of(second, first));

        ComparisonRow r = row(service.compare(ACCOUNT, BASE, TARGET), "WBC");

        assertThat(r.status()).isEqualTo(RowStatus.AMBIGUOUS);
        assertThat(r.target().observationId()).isEqualTo(first.getObservationId());
        assertThat(r.change()).isNull();
    }

    @Test
    void rowsOrderedBySortOrderThenRawByName() {
        data(List.of(obs(BASE, "WBC", "白细胞", "5", "10^9/L"), obs(BASE, null, "乙", "1", null)),
                List.of(obs(TARGET, null, "甲", "1", null), obs(TARGET, "TSH", "TSH", "2", "mIU/L")));

        List<String> keys = service.compare(ACCOUNT, BASE, TARGET).rows().stream().map(ComparisonRow::key).toList();

        assertThat(keys).containsExactly("TSH", "WBC", "RAW:乙", "RAW:甲");
    }

    @Test
    void unownedReportIsNotFound() {
        given(reportAccess.requireOwnedReport(ACCOUNT, TARGET)).willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        assertThatThrownBy(() -> service.compare(ACCOUNT, BASE, TARGET))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo("HT-3001"));
        verify(trendMapper, never()).findFormalByReport(anyLong());
    }

    @Test
    void differentProfilesAreNotFound() {
        given(reportAccess.requireOwnedReport(ACCOUNT, TARGET))
                .willReturn(report(TARGET, 12L, ProcessingStatus.CONFIRMED, LocalDate.of(2026, 10, 1)));

        assertThatThrownBy(() -> service.compare(ACCOUNT, BASE, TARGET))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(trendMapper, never()).findFormalByReport(anyLong());
    }

    @Test
    void unconfirmedReportIsNotFound() {
        given(reportAccess.requireOwnedReport(ACCOUNT, BASE))
                .willReturn(report(BASE, PROFILE, ProcessingStatus.PENDING_CONFIRMATION, null));

        assertThatThrownBy(() -> service.compare(ACCOUNT, BASE, TARGET))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(trendMapper, never()).findFormalByReport(anyLong());
    }

    @Test
    void sameReportIsValidationError() {
        assertThatThrownBy(() -> service.compare(ACCOUNT, BASE, BASE))
                .satisfies(e -> {
                    assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(((ApiException) e).getFieldErrors()).extracting("field").containsExactly("targetReportId");
                });
        verify(reportAccess, never()).requireOwnedReport(anyLong(), anyLong());
    }
}
