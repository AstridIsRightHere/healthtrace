package com.healthtrace.account.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.account.domain.AccountStatus;
import com.healthtrace.account.service.AccountService;
import com.healthtrace.account.service.AccountView;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全链路测试：精确白名单、CSRF、未登录访问、登录后会话轮换（TC-001/TC-002 反向用例）。
 * 测试密钥为测试专用随机值，与任何环境无关。
 */
@WebMvcTest(controllers = {AccountController.class, CsrfController.class, EmailVerificationController.class,
        PasswordResetController.class, SessionController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        // application.yml 的 profile 只从环境变量注入；切片测试不连数据库，显式指定 test
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class AccountSecurityWebTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private com.healthtrace.rights.audit.AuditService auditService;

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/csrf-token").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void meRequiresLoginEvenThoughAccountsPostIsPublic() throws Exception {
        mvc.perform(get("/api/v1/accounts/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
    }

    @Test
    void resendVerificationRequiresLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/email-verifications").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isUnauthorized());
        verify(accountService, never()).resendEmailVerification(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void unknownEndpointRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void stateChangeWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/v1/sessions").contentType(JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
        verify(accountService, never()).authenticate(anyString(), anyString());
    }

    @Test
    void wrongCsrfTokenIsRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        csrfToken(session);
        mvc.perform(post("/api/v1/password-reset-requests").session(session).header("X-CSRF-TOKEN", "forged")
                        .contentType(JSON).content("{\"email\":\"a@example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
    }

    @Test
    void passwordResetRequestAlwaysReturns202() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/password-reset-requests").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void expiredInvitationIsRejectedWithUniformError() throws Exception {
        willThrow(new ApiException(ErrorCode.INVALID_CREDENTIAL))
                .given(accountService).register(anyString(), anyString(), anyString());
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/accounts").session(session).header("X-CSRF-TOKEN", token).contentType(JSON)
                        .content("{\"invitationCode\":\"expired\",\"email\":\"a@example.com\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-2006"));
    }

    @Test
    void reusedVerificationTokenIsRejected() throws Exception {
        willThrow(new ApiException(ErrorCode.INVALID_CREDENTIAL))
                .given(accountService).confirmEmailVerification("used-token");
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/email-verifications/confirmations").session(session)
                        .header("X-CSRF-TOKEN", token).contentType(JSON).content("{\"token\":\"used-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-2006"));
    }

    @Test
    void lockedAccountGets429() throws Exception {
        willThrow(new ApiException(ErrorCode.TOO_MANY_REQUESTS))
                .given(accountService).authenticate(anyString(), anyString());
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/sessions").session(session).header("X-CSRF-TOKEN", token).contentType(JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"wrong password!!\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("HT-5001"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void loginThenMeThenLogout() throws Exception {
        given(accountService.authenticate("a@example.com", "correct horse battery"))
                .willReturn(new AccountPrincipal(7L));
        AccountView view = new AccountView(7L, "a@example.com", true, AccountStatus.ACTIVE);
        given(accountService.getAccount(7L)).willReturn(view);

        MockHttpSession session = new MockHttpSession();
        String preLoginToken = csrfToken(session);
        mvc.perform(post("/api/v1/sessions").session(session).header("X-CSRF-TOKEN", preLoginToken)
                        .contentType(JSON).content("{\"email\":\"a@example.com\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));

        mvc.perform(get("/api/v1/accounts/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a@example.com"));

        // 登录前的 CSRF 令牌已作废
        mvc.perform(delete("/api/v1/sessions/current").session(session).header("X-CSRF-TOKEN", preLoginToken))
                .andExpect(status().isForbidden());

        String token = csrfToken(session);
        mvc.perform(delete("/api/v1/sessions/current").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/accounts/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void validationErrorUsesUnifiedFormat() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/password-reset-requests").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"));
    }
}
