package com.healthtrace.profile.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.profile.domain.ProfilePolicy;
import com.healthtrace.profile.domain.ProfileStatus;
import com.healthtrace.profile.domain.Relationship;
import com.healthtrace.profile.service.ProfileService;
import com.healthtrace.profile.service.ProfileView;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 档案接口安全与契约测试（TC-003）：未登录 401、无 CSRF 403、未声明 400、重复建档 409、越权 404。
 */
@WebMvcTest(controllers = {ProfileController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class ProfileWebTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String BODY = "{\"relationship\":\"CHILD\",\"nickname\":\"妈妈\",\"authorizationDeclared\":true}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProfileService profileService;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/csrf-token").session(session))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private static ProfileView view() {
        return new ProfileView(11L, "妈妈", Relationship.CHILD, ProfileStatus.ACTIVE, 0L,
                LocalDateTime.of(2026, 10, 25, 10, 0), ProfilePolicy.DECLARATION_VERSION);
    }

    @Test
    void createRequiresLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
        verify(profileService, never()).createProfile(anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void currentRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/patient-profiles/current")).andExpect(status().isUnauthorized());
    }

    @Test
    void createWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/v1/patient-profiles").with(account(7L)).contentType(JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HT-2005"));
        verify(profileService, never()).createProfile(anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void createReturns201WithServerDeclaration() throws Exception {
        given(profileService.createProfile(7L, Relationship.CHILD, "妈妈", true)).willReturn(view());
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles").with(account(7L)).session(session)
                        .header("X-CSRF-TOKEN", token).contentType(JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.relationship").value("CHILD"))
                .andExpect(jsonPath("$.declarationVersion").value(ProfilePolicy.DECLARATION_VERSION));
    }

    @Test
    void undeclaredAuthorizationIsPassedAsFalseAndRejected() throws Exception {
        given(profileService.createProfile(eq(7L), eq(Relationship.SELF), any(), eq(false)))
                .willThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "请求字段校验失败",
                        List.of(new com.healthtrace.common.web.FieldErrorItem(
                                "authorizationDeclared", "AssertTrue", "请先确认授权声明"))));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles").with(account(7L)).session(session)
                        .header("X-CSRF-TOKEN", token).contentType(JSON)
                        .content("{\"relationship\":\"SELF\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("authorizationDeclared"));
    }

    @Test
    void duplicateCreateReturns409() throws Exception {
        given(profileService.createProfile(7L, Relationship.CHILD, "妈妈", true))
                .willThrow(new ApiException(ErrorCode.OPERATION_NOT_ALLOWED, "当前账号已建立档案"));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles").with(account(7L)).session(session)
                        .header("X-CSRF-TOKEN", token).contentType(JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HT-2004"));
    }

    @Test
    void unknownRelationshipIsMalformed() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles").with(account(7L)).session(session)
                        .header("X-CSRF-TOKEN", token).contentType(JSON)
                        .content("{\"relationship\":\"ADMIN\",\"authorizationDeclared\":true}"))
                .andExpect(status().isBadRequest());
        verify(profileService, never()).createProfile(anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void currentReturns404WhenNoProfileAnd200WhenPresent() throws Exception {
        given(profileService.getCurrentProfile(7L)).willThrow(new ApiException(ErrorCode.PROFILE_NOT_FOUND));
        given(profileService.getCurrentProfile(8L)).willReturn(view());
        mvc.perform(get("/api/v1/patient-profiles/current").with(account(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
        mvc.perform(get("/api/v1/patient-profiles/current").with(account(8L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("妈妈"));
    }

    @Test
    void otherAccountsProfileIsUniform404() throws Exception {
        given(profileService.getProfile(7L, 99L)).willThrow(new ApiException(ErrorCode.PROFILE_NOT_FOUND));
        mvc.perform(get("/api/v1/patient-profiles/99").with(account(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void nonNumericProfileIdIsMalformed() throws Exception {
        mvc.perform(get("/api/v1/patient-profiles/abc").with(account(7L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1002"));
    }
}
