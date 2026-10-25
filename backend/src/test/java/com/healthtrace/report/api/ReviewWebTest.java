package com.healthtrace.report.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.report.domain.OcrJobStatus;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.service.ReviewService;
import com.healthtrace.report.service.ReviewViews.ConfirmCommand;
import com.healthtrace.report.service.ReviewViews.ConfirmResult;
import com.healthtrace.report.service.ReviewViews.DraftReport;
import com.healthtrace.report.service.ReviewViews.DraftView;
import com.healthtrace.report.service.ReviewViews.OcrJobView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OCR 与草稿确认接口安全与契约测试：未登录 401、无 CSRF 403、缺少 OCR 同意 403、越权 404、确认校验与版本冲突。
 */
@WebMvcTest(controllers = {ReviewController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class ReviewWebTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReviewService reviewService;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/csrf-token").session(session))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private static OcrJobView jobView() {
        return new OcrJobView(31L, 21L, OcrJobStatus.PENDING, 0, 3, null, null,
                LocalDateTime.of(2026, 10, 25, 10, 0), null);
    }

    // ------------------------------------------------------------ ocr-jobs

    @Test
    void submitOcrRequiresLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/reports/21/ocr-jobs?ocrConsent=true").session(session)
                        .header("X-CSRF-TOKEN", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
        verify(reviewService, never()).submitOcr(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void submitOcrWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/v1/reports/21/ocr-jobs?ocrConsent=true").with(account(7L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
        verify(reviewService, never()).submitOcr(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void submitOcrWithoutConsentPassesFalseAndReturns403() throws Exception {
        given(reviewService.submitOcr(7L, 21L, false))
                .willThrow(new ApiException(ErrorCode.OCR_CONSENT_REQUIRED));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/reports/21/ocr-jobs").session(session).with(account(7L))
                        .header("X-CSRF-TOKEN", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-4003"));
        verify(reviewService, never()).submitOcr(7L, 21L, true);
    }

    @Test
    void submitOcrWithConsentReturns202() throws Exception {
        given(reviewService.submitOcr(7L, 21L, true)).willReturn(jobView());
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/reports/21/ocr-jobs?ocrConsent=true").session(session).with(account(7L))
                        .header("X-CSRF-TOKEN", token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.maxAttempts").value(3));
    }

    @Test
    void submitOcrOnOthersReportIs404() throws Exception {
        given(reviewService.submitOcr(8L, 21L, true)).willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/reports/21/ocr-jobs?ocrConsent=true").session(session).with(account(8L))
                        .header("X-CSRF-TOKEN", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    // ------------------------------------------------------------ draft

    @Test
    void draftRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/reports/21/draft"))
                .andExpect(status().isUnauthorized());
        verify(reviewService, never()).getDraft(anyLong(), anyLong());
    }

    @Test
    void draftOfOthersReportIs404() throws Exception {
        given(reviewService.getDraft(8L, 21L)).willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/reports/21/draft").with(account(8L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void draftReturnsReportAndObservations() throws Exception {
        DraftReport report = new DraftReport(21L, 11L, "血常规", LocalDate.of(2026, 10, 20),
                ProcessingStatus.PENDING_CONFIRMATION, 2L);
        given(reviewService.getDraft(7L, 21L)).willReturn(new DraftView(report, List.of(), null, null, List.of()));
        mvc.perform(get("/api/v1/reports/21/draft").with(account(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.version").value(2))
                .andExpect(jsonPath("$.report.processingStatus").value("PENDING_CONFIRMATION"));
    }

    // ------------------------------------------------------------ confirmation

    private static final String CONFIRM_BODY = """
            {"version":2,"examinationDate":"2026-10-20",
             "observations":[{"id":41,"originalName":"白细胞计数","originalValue":"6.5","unitText":"10^9/L","referenceText":"3.5-9.5"}]}
            """;

    @Test
    void confirmWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/v1/reports/21/confirmation").with(account(7L)).contentType(JSON).content(CONFIRM_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
        verify(reviewService, never()).confirm(anyLong(), anyLong(), any());
    }

    @Test
    void confirmReturnsConfirmedResult() throws Exception {
        given(reviewService.confirm(anyLong(), anyLong(), any(ConfirmCommand.class)))
                .willReturn(new ConfirmResult(21L, ProcessingStatus.CONFIRMED, 3L, LocalDate.of(2026, 10, 20), 1));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/reports/21/confirmation").session(session).with(account(7L))
                        .header("X-CSRF-TOKEN", token).contentType(JSON).content(CONFIRM_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void confirmVersionConflictIs409() throws Exception {
        given(reviewService.confirm(anyLong(), anyLong(), any(ConfirmCommand.class)))
                .willThrow(new ApiException(ErrorCode.VERSION_CONFLICT));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/reports/21/confirmation").session(session).with(account(7L))
                        .header("X-CSRF-TOKEN", token).contentType(JSON).content(CONFIRM_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HT-3002"));
    }

    @Test
    void confirmValidationErrorsCarryRowFields() throws Exception {
        given(reviewService.confirm(anyLong(), anyLong(), any(ConfirmCommand.class)))
                .willThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "请求字段校验失败",
                        List.of(new FieldErrorItem("observations[0].originalValue", "REQUIRED", "请填写结果"))));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/reports/21/confirmation").session(session).with(account(7L))
                        .header("X-CSRF-TOKEN", token).contentType(JSON).content(CONFIRM_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("observations[0].originalValue"));
    }
}
