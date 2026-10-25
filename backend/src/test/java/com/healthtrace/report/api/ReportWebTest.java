package com.healthtrace.report.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.report.domain.DuplicateDecision;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.ReportListFilter;
import com.healthtrace.report.domain.ReportType;
import com.healthtrace.report.service.ReportService;
import com.healthtrace.report.service.ReportViews.PhotoContent;
import com.healthtrace.report.service.ReportViews.PhotoUploadResult;
import com.healthtrace.report.service.ReportViews.PhotoVariant;
import com.healthtrace.report.service.ReportViews.PhotoView;
import com.healthtrace.report.service.ReportViews.ReportView;
import com.healthtrace.report.service.ReportViews.UploadOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 报告上传接口安全与契约测试（TC-004/TC-005）：未登录 401、无 CSRF 403、越权 404、413、415、未验证邮箱 403、重复提示。
 */
@WebMvcTest(controllers = {ReportController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class ReportWebTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReportService reportService;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/csrf-token").session(session))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private static MockMultipartFile original() {
        return new MockMultipartFile("original", "a.jpg", "image/jpeg", JPEG);
    }

    private static ReportView reportView() {
        return new ReportView(21L, 11L, "血常规", ReportType.UNKNOWN, LocalDate.of(2026, 10, 20),
                ProcessingStatus.UPLOADING, LocalDateTime.of(2026, 10, 25, 10, 0));
    }

    // ------------------------------------------------------------ create report

    @Test
    void createReportRequiresLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles/11/reports").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
        verify(reportService, never()).createReport(anyLong(), anyLong(), any(), any());
    }

    @Test
    void createReportWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/v1/patient-profiles/11/reports").with(account(7L)).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
        verify(reportService, never()).createReport(anyLong(), anyLong(), any(), any());
    }

    @Test
    void createReportReturns201() throws Exception {
        given(reportService.createReport(7L, 11L, "血常规", LocalDate.of(2026, 10, 20))).willReturn(reportView());
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles/11/reports").with(account(7L)).session(session)
                        .header("X-CSRF-TOKEN", token).contentType(JSON)
                        .content("{\"title\":\"血常规\",\"examinationDate\":\"2026-10-20\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.processingStatus").value("UPLOADING"));
    }

    @Test
    void createReportOnOthersProfileIs404() throws Exception {
        given(reportService.createReport(eq(7L), eq(99L), any(), any()))
                .willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles/99/reports").with(account(7L)).session(session)
                        .header("X-CSRF-TOKEN", token).contentType(JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void createReportByUnverifiedAccountIs403() throws Exception {
        given(reportService.createReport(eq(7L), eq(11L), any(), any()))
                .willThrow(new ApiException(ErrorCode.EMAIL_NOT_VERIFIED));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles/11/reports").with(account(7L)).session(session)
                        .header("X-CSRF-TOKEN", token).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2003"))
                .andExpect(jsonPath("$.message").value("请先验证邮箱"));
    }

    // ------------------------------------------------------------ upload

    @Test
    void uploadWithoutCsrfIsRejected() throws Exception {
        mvc.perform(multipart("/api/v1/reports/21/photos").file(original()).param("pageOrder", "1")
                        .with(account(7L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
        verify(reportService, never()).uploadPhoto(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void uploadReturns201WhenSaved() throws Exception {
        given(reportService.uploadPhoto(eq(7L), eq(21L), any(), any(), eq(1), eq(DuplicateDecision.ASK)))
                .willReturn(new PhotoUploadResult(UploadOutcome.UPLOADED,
                        new PhotoView(99L, 21L, 1, "image/jpeg", JPEG.length, false), List.of()));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(multipart("/api/v1/reports/21/photos").file(original()).param("pageOrder", "1")
                        .with(account(7L)).session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("UPLOADED"))
                .andExpect(jsonPath("$.photo.id").value(99));
    }

    @Test
    void uploadDuplicateSuspectedReturns200WithExistingReports() throws Exception {
        given(reportService.uploadPhoto(eq(7L), eq(21L), any(), any(), eq(1), eq(DuplicateDecision.ASK)))
                .willReturn(new PhotoUploadResult(UploadOutcome.DUPLICATE_SUSPECTED, null, List.of(5L)));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(multipart("/api/v1/reports/21/photos").file(original()).param("pageOrder", "1")
                        .with(account(7L)).session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DUPLICATE_SUSPECTED"))
                .andExpect(jsonPath("$.existingReportIds[0]").value(5));
    }

    @Test
    void uploadKeepDecisionIsPassedThrough() throws Exception {
        given(reportService.uploadPhoto(eq(7L), eq(21L), any(), any(), eq(1), eq(DuplicateDecision.KEEP)))
                .willReturn(new PhotoUploadResult(UploadOutcome.UPLOADED,
                        new PhotoView(100L, 21L, 1, "image/jpeg", JPEG.length, false), List.of()));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(multipart("/api/v1/reports/21/photos").file(original()).param("pageOrder", "1")
                        .param("duplicateDecision", "keep")
                        .with(account(7L)).session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isCreated());
    }

    @Test
    void uploadRejectsUnknownDuplicateDecision() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(multipart("/api/v1/reports/21/photos").file(original()).param("pageOrder", "1")
                        .param("duplicateDecision", "MERGE")
                        .with(account(7L)).session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("duplicateDecision"));
    }

    @Test
    void uploadWithoutOriginalPartIs400() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(multipart("/api/v1/reports/21/photos").param("pageOrder", "1")
                        .with(account(7L)).session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("original"));
    }

    @Test
    void uploadTooLargeIs413() throws Exception {
        given(reportService.uploadPhoto(anyLong(), anyLong(), any(), any(), any(), any()))
                .willThrow(new ApiException(ErrorCode.FILE_TOO_LARGE));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(multipart("/api/v1/reports/21/photos").file(original()).param("pageOrder", "1")
                        .with(account(7L)).session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("HT-1003"));
    }

    @Test
    void uploadUnsupportedTypeIs415() throws Exception {
        given(reportService.uploadPhoto(anyLong(), anyLong(), any(), any(), any(), any()))
                .willThrow(new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(multipart("/api/v1/reports/21/photos").file(original()).param("pageOrder", "1")
                        .with(account(7L)).session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("HT-1004"));
    }

    @Test
    void uploadToOthersReportIs404() throws Exception {
        given(reportService.uploadPhoto(eq(7L), eq(999L), any(), any(), any(), any()))
                .willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(multipart("/api/v1/reports/999/photos").file(original()).param("pageOrder", "1")
                        .with(account(7L)).session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    // ------------------------------------------------------------ list / access

    @Test
    void listRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/patient-profiles/11/reports").param("status", "DRAFT"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listDraftDelegatesWithFilter() throws Exception {
        given(reportService.listReports(7L, 11L, ReportListFilter.DRAFT)).willReturn(List.of());
        mvc.perform(get("/api/v1/patient-profiles/11/reports").param("status", "DRAFT").with(account(7L)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void accessPhotoRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/photos/99/access")).andExpect(status().isUnauthorized());
        verify(reportService, never()).openPhoto(anyLong(), anyLong(), any());
    }

    @Test
    void accessOthersPhotoIs404() throws Exception {
        given(reportService.openPhoto(7L, 99L, PhotoVariant.PREVIEW))
                .willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/photos/99/access").with(account(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void accessLocalPhotoStreamsWithNoStore() throws Exception {
        given(reportService.openPhoto(7L, 99L, PhotoVariant.ORIGINAL))
                .willReturn(new PhotoContent(null, new ByteArrayInputStream(JPEG), "image/jpeg", JPEG.length));
        mvc.perform(get("/api/v1/photos/99/access").param("variant", "original").with(account(7L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().bytes(JPEG));
    }

    @Test
    void accessSignedPhotoRedirects() throws Exception {
        given(reportService.openPhoto(7L, 99L, PhotoVariant.PREVIEW))
                .willReturn(new PhotoContent(URI.create("https://oss.example.com/x?sig=1"), null, "image/jpeg", 10));
        mvc.perform(get("/api/v1/photos/99/access").with(account(7L)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://oss.example.com/x?sig=1"));
    }
}
