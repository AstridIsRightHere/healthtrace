package com.healthtrace.report.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.report.service.ReportDeletionService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DELETE /api/v1/reports/{reportId}：未登录 401、无 CSRF 403、成功 204、越权 404、已确认/识别中 409。
 */
@WebMvcTest(controllers = {ReportDeletionController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class ReportDeletionWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReportDeletionService deletionService;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/csrf-token").session(session))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void deleteRequiresLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(delete("/api/v1/reports/21").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
        verify(deletionService, never()).deleteUnconfirmed(anyLong(), anyLong());
    }

    @Test
    void deleteWithoutCsrfIsRejected() throws Exception {
        mvc.perform(delete("/api/v1/reports/21").with(account(7)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
        verify(deletionService, never()).deleteUnconfirmed(anyLong(), anyLong());
    }

    @Test
    void deleteOwnUnconfirmedReturns204() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(delete("/api/v1/reports/21").session(session).header("X-CSRF-TOKEN", token).with(account(7)))
                .andExpect(status().isNoContent());
        verify(deletionService).deleteUnconfirmed(7L, 21L);
    }

    @Test
    void deleteOthersReportIs404() throws Exception {
        willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).given(deletionService).deleteUnconfirmed(7L, 99L);
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(delete("/api/v1/reports/99").session(session).header("X-CSRF-TOKEN", token).with(account(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void deleteConfirmedReportIs409() throws Exception {
        willThrow(new ApiException(ErrorCode.REPORT_STATE_CONFLICT)).given(deletionService).deleteUnconfirmed(7L, 21L);
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(delete("/api/v1/reports/21").session(session).header("X-CSRF-TOKEN", token).with(account(7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HT-4002"));
    }
}
