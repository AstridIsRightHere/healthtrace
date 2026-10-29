package com.healthtrace.report.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.report.service.ReportRevisionService;
import com.healthtrace.report.service.RevisionViews.ChangeType;
import com.healthtrace.report.service.RevisionViews.ObservationSnapshot;
import com.healthtrace.report.service.RevisionViews.ReviseResult;
import com.healthtrace.report.service.RevisionViews.RevisionChange;
import com.healthtrace.report.service.RevisionViews.RevisionView;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修改已确认报告（FR-014）接口：未登录 401、无 CSRF 403、越权 404、无修改 400、版本冲突 409、修改记录读取。
 */
@WebMvcTest(controllers = {ReportRevisionController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class ReportRevisionWebTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String BODY = """
            {"version":3,"examinationDate":"2026-10-20","reason":"录入错误",
             "observations":[{"id":1,"originalName":"血红蛋白","originalValue":"150","unitText":"g/L"}]}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReportRevisionService revisionService;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/csrf-token").session(session))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void reviseRequiresLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(patch("/api/v1/reports/21").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(revisionService, never()).revise(anyLong(), anyLong(), any());
    }

    @Test
    void reviseRequiresCsrf() throws Exception {
        mvc.perform(patch("/api/v1/reports/21").with(account(7L)).contentType(JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(revisionService, never()).revise(anyLong(), anyLong(), any());
    }

    @Test
    void reviseOthersReportIsNotFound() throws Exception {
        given(revisionService.revise(any(Long.class), any(Long.class), any()))
                .willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(patch("/api/v1/reports/88").with(account(7L)).session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void reviseSucceeds() throws Exception {
        given(revisionService.revise(any(Long.class), any(Long.class), any()))
                .willReturn(new ReviseResult(21L, 4L, LocalDate.of(2026, 10, 20), 99L, 1));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(patch("/api/v1/reports/21").with(account(7L)).session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.revisionId").value(99))
                .andExpect(jsonPath("$.changedCount").value(1));
    }

    @Test
    void noChangeIsBadRequest() throws Exception {
        given(revisionService.revise(any(Long.class), any(Long.class), any()))
                .willThrow(new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                        List.of(new FieldErrorItem("observations", "NO_CHANGE", "没有检测到修改内容"))));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(patch("/api/v1/reports/21").with(account(7L)).session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].reasonCode").value("NO_CHANGE"));
    }

    @Test
    void staleVersionIsConflict() throws Exception {
        given(revisionService.revise(any(Long.class), any(Long.class), any()))
                .willThrow(new ApiException(ErrorCode.VERSION_CONFLICT));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(patch("/api/v1/reports/21").with(account(7L)).session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HT-3002"));
    }

    @Test
    void revisionsRequireLogin() throws Exception {
        mvc.perform(get("/api/v1/reports/21/revisions")).andExpect(status().isUnauthorized());
    }

    @Test
    void revisionsOfOthersReportIsNotFound() throws Exception {
        given(revisionService.listRevisions(7L, 88L)).willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/reports/88/revisions").with(account(7L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void revisionsAreListed() throws Exception {
        given(revisionService.listRevisions(7L, 21L)).willReturn(List.of(new RevisionView(99L, 3L, 4L, "录入错误",
                LocalDateTime.of(2026, 10, 27, 8, 0), null,
                List.of(new RevisionChange(ChangeType.UPDATED,
                        new ObservationSnapshot("血红蛋白", "140", "g/L", null),
                        new ObservationSnapshot("血红蛋白", "150", "g/L", null))))));
        mvc.perform(get("/api/v1/reports/21/revisions").with(account(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toVersion").value(4))
                .andExpect(jsonPath("$[0].changes[0].type").value("UPDATED"))
                .andExpect(jsonPath("$[0].changes[0].before.originalValue").value("140"))
                .andExpect(jsonPath("$[0].changes[0].after.originalValue").value("150"));
    }
}
