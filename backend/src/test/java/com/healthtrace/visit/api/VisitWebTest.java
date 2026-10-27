package com.healthtrace.visit.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.visit.service.VisitService;
import com.healthtrace.visit.service.VisitViews.LinkedReportView;
import com.healthtrace.visit.service.VisitViews.VisitInput;
import com.healthtrace.visit.service.VisitViews.VisitView;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 就诊时间线接口：登录、CSRF、201/204、越权 404、缺版本号 400。
 */
@WebMvcTest(controllers = {VisitController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class VisitWebTest {

    private static final String BODY =
            "{\"visitDate\":\"2026-10-27\",\"hospitalText\":\"市第一医院\",\"department\":\"呼吸内科\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private VisitService visitService;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/csrf-token").session(session))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private static VisitView view(List<LinkedReportView> reports) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 27, 12, 0);
        return new VisitView(31, 11, LocalDate.of(2026, 10, 27), "市第一医院", "呼吸内科", null, 0, t, t, reports);
    }

    @Test
    void listRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/patient-profiles/11/visits")).andExpect(status().isUnauthorized());
    }

    @Test
    void listReturnsVisits() throws Exception {
        given(visitService.list(7, 11, 3)).willReturn(List.of(view(List.of())));
        mvc.perform(get("/api/v1/patient-profiles/11/visits").param("limit", "3").with(account(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hospitalText").value("市第一医院"))
                .andExpect(jsonPath("$[0].visitDate").value("2026-10-27"));
    }

    @Test
    void listOtherProfileIs404() throws Exception {
        given(visitService.list(7, 12, null)).willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/patient-profiles/12/visits").with(account(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void createRequiresCsrf() throws Exception {
        mvc.perform(post("/api/v1/patient-profiles/11/visits").with(account(7))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(visitService, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    void createReturns201() throws Exception {
        given(visitService.create(eq(7L), eq(11L), any(VisitInput.class))).willReturn(view(List.of()));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles/11/visits").session(session).header("X-CSRF-TOKEN", token)
                        .with(account(7)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.department").value("呼吸内科"));
    }

    @Test
    void patchWithoutVersionIs400() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(patch("/api/v1/visits/31").session(session).header("X-CSRF-TOKEN", token)
                        .with(account(7)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"));
        verify(visitService, never()).update(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void getOthersVisitIs404() throws Exception {
        given(visitService.get(8, 31)).willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/visits/31").with(account(8)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void deleteReturns204() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(delete("/api/v1/visits/31").session(session).header("X-CSRF-TOKEN", token).with(account(7)))
                .andExpect(status().isNoContent());
        verify(visitService).delete(7, 31);
    }

    @Test
    void linkReturnsVisitWithReports() throws Exception {
        given(visitService.linkReport(7, 31, 21)).willReturn(view(List.of(
                new LinkedReportView(21, "血常规", null, LocalDate.of(2026, 10, 20), null))));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(put("/api/v1/visits/31/reports/21").session(session).header("X-CSRF-TOKEN", token)
                        .with(account(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].id").value(21));
    }

    @Test
    void linkForeignReportIs404() throws Exception {
        willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).given(visitService).linkReport(7, 31, 22);
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(put("/api/v1/visits/31/reports/22").session(session).header("X-CSRF-TOKEN", token)
                        .with(account(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void unlinkReturnsVisit() throws Exception {
        given(visitService.unlinkReport(7, 31, 21)).willReturn(view(List.of()));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(delete("/api/v1/visits/31/reports/21").session(session).header("X-CSRF-TOKEN", token)
                        .with(account(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports").isEmpty());
    }
}
