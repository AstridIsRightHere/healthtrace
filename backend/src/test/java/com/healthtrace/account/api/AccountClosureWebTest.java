package com.healthtrace.account.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.account.service.AccountClosureService;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditOutcome;
import com.healthtrace.rights.audit.AuditService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 账号注销接口（FR-017）：登录 + CSRF + 密码；成功 204 且会话失效，密码错误 401 并记录拒绝审计。 */
@WebMvcTest(controllers = {AccountClosureController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class AccountClosureWebTest {

    private static final String URL = "/api/v1/account-closure";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccountClosureService closureService;

    @MockitoBean
    private AuditService auditService;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/csrf-token").session(session))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void requiresLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(URL).session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content("{\"password\":\"secret-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
        verifyNoInteractions(closureService);
    }

    @Test
    void requiresCsrf() throws Exception {
        mvc.perform(post(URL).with(account(7)).contentType(JSON).content("{\"password\":\"secret-pass\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
        verifyNoInteractions(closureService);
    }

    @Test
    void blankPasswordFailsValidation() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(URL).session(session).header("X-CSRF-TOKEN", token).with(account(7))
                        .contentType(JSON).content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"));
        verifyNoInteractions(closureService);
    }

    @Test
    void successReturns204AndInvalidatesSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        given(closureService.close(7L, "secret-pass")).willReturn(12);

        mvc.perform(post(URL).session(session).header("X-CSRF-TOKEN", token).with(account(7))
                        .contentType(JSON).content("{\"password\":\"secret-pass\"}"))
                .andExpect(status().isNoContent());

        verify(closureService).close(7L, "secret-pass");
        assertThat(session.isInvalid()).isTrue();
        // 成功审计由服务在事务内写入，控制器不重复记录
        verifyNoInteractions(auditService);
    }

    @Test
    void wrongPasswordReturns401AndRecordsDeniedAudit() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        willThrow(new ApiException(ErrorCode.BAD_CREDENTIALS)).given(closureService).close(anyLong(), anyString());

        mvc.perform(post(URL).session(session).header("X-CSRF-TOKEN", token).with(account(7))
                        .contentType(JSON).content("{\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2002"));

        verify(auditService).record(7L, AuditAction.ACCOUNT_CLOSE, "account:7", AuditOutcome.DENIED);
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void otherFailuresDoNotRecordDeniedAudit() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        willThrow(new ApiException(ErrorCode.OPERATION_NOT_ALLOWED)).given(closureService).close(anyLong(), anyString());

        mvc.perform(post(URL).session(session).header("X-CSRF-TOKEN", token).with(account(7))
                        .contentType(JSON).content("{\"password\":\"secret-pass\"}"))
                .andExpect(status().isConflict());

        verify(auditService, never()).record(anyLong(), org.mockito.ArgumentMatchers.any(),
                anyString(), org.mockito.ArgumentMatchers.any());
        assertThat(session.isInvalid()).isFalse();
    }
}
