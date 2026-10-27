package com.healthtrace.report.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.indicator.service.TrendViews.Change;
import com.healthtrace.report.compare.ComparisonViews.ComparisonRow;
import com.healthtrace.report.compare.ComparisonViews.ComparisonView;
import com.healthtrace.report.compare.ComparisonViews.ObservationCell;
import com.healthtrace.report.compare.ComparisonViews.ReportBrief;
import com.healthtrace.report.compare.ComparisonViews.RowStatus;
import com.healthtrace.report.compare.ReportComparisonService;
import com.healthtrace.report.domain.ResultKind;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 报告对比接口契约测试（FR-010/011）：未登录 401、越权 404、缺参 400、响应结构。
 */
@WebMvcTest(controllers = {ReportComparisonController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class ReportComparisonWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReportComparisonService comparisonService;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    @Test
    void compareRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/report-comparisons").param("baseReportId", "1").param("targetReportId", "2"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
        verify(comparisonService, never()).compare(anyLong(), anyLong(), anyLong());
    }

    @Test
    void compareReturnsContractShape() throws Exception {
        ObservationCell base = new ObservationCell(10L, "2.1", new BigDecimal("2.1"), ResultKind.EXACT,
                "mIU/L", "血清", null, "0.27-4.2", null);
        ObservationCell target = new ObservationCell(20L, "2.4", new BigDecimal("2.4"), ResultKind.EXACT,
                "mIU/L", "血清", null, "0.27-4.2", null);
        ComparisonRow row = new ComparisonRow("TSH", "促甲状腺激素", true, RowStatus.COMPARABLE, base, target,
                new Change(new BigDecimal("0.3"), new BigDecimal("14.29"), null));
        ComparisonRow only = new ComparisonRow("RAW:尿酸", "尿酸", false, RowStatus.TARGET_ONLY, null, target, null);
        given(comparisonService.compare(7L, 1L, 2L)).willReturn(new ComparisonView(
                new ReportBrief(1L, "七月体检", LocalDate.of(2026, 7, 1)),
                new ReportBrief(2L, "十月体检", LocalDate.of(2026, 10, 1)),
                List.of(row, only)));

        mvc.perform(get("/api/v1/report-comparisons").with(account(7L))
                        .param("baseReportId", "1").param("targetReportId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base.id").value(1))
                .andExpect(jsonPath("$.base.examinationDate").value("2026-07-01"))
                .andExpect(jsonPath("$.target.title").value("十月体检"))
                .andExpect(jsonPath("$.rows[0].key").value("TSH"))
                .andExpect(jsonPath("$.rows[0].standard").value(true))
                .andExpect(jsonPath("$.rows[0].status").value("COMPARABLE"))
                .andExpect(jsonPath("$.rows[0].base.observationId").value(10))
                .andExpect(jsonPath("$.rows[0].base.numericValue").value(2.1))
                .andExpect(jsonPath("$.rows[0].base.resultKind").value("EXACT"))
                .andExpect(jsonPath("$.rows[0].target.unitText").value("mIU/L"))
                .andExpect(jsonPath("$.rows[0].change.absolute").value(0.3))
                .andExpect(jsonPath("$.rows[0].change.percent").value(14.29))
                .andExpect(jsonPath("$.rows[1].status").value("TARGET_ONLY"))
                .andExpect(jsonPath("$.rows[1].base").doesNotExist())
                .andExpect(jsonPath("$.rows[1].change").doesNotExist());
    }

    @Test
    void inaccessibleReportIsNotFound() throws Exception {
        given(comparisonService.compare(7L, 1L, 99L)).willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/report-comparisons").with(account(7L))
                        .param("baseReportId", "1").param("targetReportId", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void missingParamIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/report-comparisons").with(account(7L)).param("baseReportId", "1"))
                .andExpect(status().isBadRequest());
        verify(comparisonService, never()).compare(anyLong(), anyLong(), anyLong());
    }

    @Test
    void malformedParamIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/report-comparisons").with(account(7L))
                        .param("baseReportId", "abc").param("targetReportId", "2"))
                .andExpect(status().isBadRequest());
    }
}
