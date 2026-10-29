package com.healthtrace.indicator.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.indicator.service.TrendService;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.indicator.service.TrendViews.Change;
import com.healthtrace.indicator.service.TrendViews.DateRange;
import com.healthtrace.indicator.service.TrendViews.LatestObservation;
import com.healthtrace.indicator.service.TrendViews.MetricInfo;
import com.healthtrace.indicator.service.TrendViews.MetricLatestSummaries;
import com.healthtrace.indicator.service.TrendViews.MetricLatestSummary;
import com.healthtrace.indicator.service.TrendViews.TrendView;
import com.healthtrace.report.domain.AbnormalFlag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 趋势接口：未登录 401、越权 404、参数透传与日期格式校验。 */
@WebMvcTest(controllers = {TrendController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class TrendWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TrendService trendService;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    @Test
    void trendRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/patient-profiles/5/trends").param("metricCode", "WBC"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
        verify(trendService, never()).getTrend(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void metricsRequireLogin() throws Exception {
        mvc.perform(get("/api/v1/patient-profiles/5/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherProfileTrendIsNotFound() throws Exception {
        given(trendService.getTrend(7L, 99L, "WBC", "30d", null, null))
                .willThrow(new ApiException(ErrorCode.PROFILE_NOT_FOUND));
        mvc.perform(get("/api/v1/patient-profiles/99/trends").with(account(7))
                        .param("metricCode", "WBC").param("range", "30d"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void otherProfileMetricsIsNotFound() throws Exception {
        given(trendService.listMetrics(7L, 99L)).willThrow(new ApiException(ErrorCode.PROFILE_NOT_FOUND));
        mvc.perform(get("/api/v1/patient-profiles/99/metrics").with(account(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void customRangeDatesArePassedThrough() throws Exception {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 1);
        given(trendService.getTrend(7L, 5L, "WBC", "custom", from, to)).willReturn(new TrendView(
                new MetricInfo("WBC", "白细胞计数", "10^9/L", null), new DateRange(from, to), List.of(), false));
        mvc.perform(get("/api/v1/patient-profiles/5/trends").with(account(7))
                        .param("metricCode", "WBC").param("range", "custom")
                        .param("from", "2025-01-01").param("to", "2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric.code").value("WBC"))
                .andExpect(jsonPath("$.range.from").value("2025-01-01"))
                .andExpect(jsonPath("$.unitWarning").value(false));
    }

    @Test
    void malformedDateIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/patient-profiles/5/trends").with(account(7))
                        .param("metricCode", "WBC").param("range", "custom").param("from", "2025/01/01"))
                .andExpect(status().isBadRequest());
        verify(trendService, never()).getTrend(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void summariesRequireLogin() throws Exception {
        mvc.perform(get("/api/v1/patient-profiles/5/metric-summaries").param("codes", "WBC"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
        verify(trendService, never()).getLatestSummaries(anyLong(), anyLong(), any());
    }

    @Test
    void otherProfileSummariesIsNotFound() throws Exception {
        given(trendService.getLatestSummaries(7L, 99L, "WBC"))
                .willThrow(new ApiException(ErrorCode.PROFILE_NOT_FOUND));
        mvc.perform(get("/api/v1/patient-profiles/99/metric-summaries").with(account(7)).param("codes", "WBC"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void summariesShape() throws Exception {
        given(trendService.getLatestSummaries(7L, 5L, "WBC,HGB")).willReturn(new MetricLatestSummaries(List.of(
                new MetricLatestSummary("WBC", "白细胞计数", "10^9/L",
                        new LatestObservation(11L, 3L, LocalDate.of(2026, 10, 10), "6.5",
                                new BigDecimal("6.5"), "10^9/L", AbnormalFlag.NORMAL),
                        new Change(new BigDecimal("0.5"), new BigDecimal("8.33"), null)),
                new MetricLatestSummary("HGB", "血红蛋白", "g/L", null, null))));
        mvc.perform(get("/api/v1/patient-profiles/5/metric-summaries").with(account(7)).param("codes", "WBC,HGB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].metricCode").value("WBC"))
                .andExpect(jsonPath("$.items[0].standardName").value("白细胞计数"))
                .andExpect(jsonPath("$.items[0].standardUnit").value("10^9/L"))
                .andExpect(jsonPath("$.items[0].latest.observationId").value(11))
                .andExpect(jsonPath("$.items[0].latest.reportId").value(3))
                .andExpect(jsonPath("$.items[0].latest.examinationDate").value("2026-10-10"))
                .andExpect(jsonPath("$.items[0].latest.originalValue").value("6.5"))
                .andExpect(jsonPath("$.items[0].latest.numericValue").value(6.5))
                .andExpect(jsonPath("$.items[0].latest.unitText").value("10^9/L"))
                .andExpect(jsonPath("$.items[0].latest.abnormalFlag").value("NORMAL"))
                .andExpect(jsonPath("$.items[0].change.absolute").value(0.5))
                .andExpect(jsonPath("$.items[0].change.percent").value(8.33))
                .andExpect(jsonPath("$.items[1].metricCode").value("HGB"))
                .andExpect(jsonPath("$.items[1].latest").value(nullValue()))
                .andExpect(jsonPath("$.items[1].change").value(nullValue()));
    }

    @Test
    void summariesValidationIsBadRequest() throws Exception {
        given(trendService.getLatestSummaries(7L, 5L, null)).willThrow(new ApiException(ErrorCode.VALIDATION_FAILED,
                "请指定指标", List.of(new FieldErrorItem("codes", "NotBlank", "请指定指标"))));
        mvc.perform(get("/api/v1/patient-profiles/5/metric-summaries").with(account(7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("codes"));
    }
}
