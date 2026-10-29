package com.healthtrace.report.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.report.service.ReportListRemovalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST / DELETE /api/v1/reports/{reportId}/list-removals：未登录 401、无 CSRF 403、成功 204、越权 404、恢复冲突 409。
 */
@WebMvcTest(controllers = {ReportListRemovalController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class ReportListRemovalWebTest {

    private static final String PATH = "/api/v1/reports/21/list-removals";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReportListRemovalService service;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/csrf-token").session(session))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void removeRequiresLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(PATH).session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
        verify(service, never()).remove(anyLong(), anyLong());
    }

    @Test
    void removeWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post(PATH).with(account(7)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
        verify(service, never()).remove(anyLong(), anyLong());
    }

    @Test
    void removeReturns204() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(PATH).session(session).header("X-CSRF-TOKEN", token).with(account(7)))
                .andExpect(status().isNoContent());
        verify(service).remove(7L, 21L);
    }

    @Test
    void removeOthersReportIs404() throws Exception {
        willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).given(service).remove(7L, 21L);
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(PATH).session(session).header("X-CSRF-TOKEN", token).with(account(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void restoreWithoutCsrfIsRejected() throws Exception {
        mvc.perform(delete(PATH).with(account(7)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
        verify(service, never()).restore(anyLong(), anyLong());
    }

    @Test
    void restoreReturns204() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(delete(PATH).session(session).header("X-CSRF-TOKEN", token).with(account(7)))
                .andExpect(status().isNoContent());
        verify(service).restore(7L, 21L);
    }

    @Test
    void restoreNormalReportIs409() throws Exception {
        willThrow(new ApiException(ErrorCode.REPORT_STATE_CONFLICT, "报告不在回收站中"))
                .given(service).restore(7L, 21L);
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(delete(PATH).session(session).header("X-CSRF-TOKEN", token).with(account(7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HT-4002"));
    }
}
