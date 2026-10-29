package com.healthtrace.indicator.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.indicator.service.FollowService;
import com.healthtrace.indicator.service.FollowViews.FollowedMetricView;
import com.healthtrace.indicator.service.FollowViews.MetricDefinitionView;
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

import static org.mockito.ArgumentMatchers.any;
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
 * 关注指标接口：登录、CSRF、越权 404、响应结构、校验 400。
 */
@WebMvcTest(controllers = {FollowController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class FollowWebTest {

    private static final String PATH = "/api/v1/patient-profiles/11/followed-metrics";
    private static final String BODY = "{\"metricCodes\":[\"HGB\",\"WBC\"]}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FollowService followService;

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/csrf-token").session(session))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void listRequiresLogin() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
    }

    @Test
    void definitionsRequireLogin() throws Exception {
        mvc.perform(get("/api/v1/metric-definitions")).andExpect(status().isUnauthorized());
    }

    @Test
    void listReturnsOrderedMetrics() throws Exception {
        given(followService.list(7, 11)).willReturn(List.of(
                new FollowedMetricView("WBC", "白细胞计数", 0),
                new FollowedMetricView("HGB", "血红蛋白", 1)));
        mvc.perform(get(PATH).with(account(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].metricCode").value("WBC"))
                .andExpect(jsonPath("$[0].name").value("白细胞计数"))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[1].metricCode").value("HGB"))
                .andExpect(jsonPath("$[1].sortOrder").value(1));
    }

    @Test
    void listOtherProfileIs404() throws Exception {
        given(followService.list(7, 12)).willThrow(new ApiException(ErrorCode.PROFILE_NOT_FOUND));
        mvc.perform(get("/api/v1/patient-profiles/12/followed-metrics").with(account(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void definitionsReturnDictionary() throws Exception {
        given(followService.listDefinitions()).willReturn(List.of(new MetricDefinitionView("WBC", "白细胞计数")));
        mvc.perform(get("/api/v1/metric-definitions").with(account(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("WBC"))
                .andExpect(jsonPath("$[0].name").value("白细胞计数"));
    }

    @Test
    void replaceRequiresLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(PATH).session(session).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(followService, never()).replace(anyLong(), anyLong(), any());
    }

    @Test
    void replaceRequiresCsrf() throws Exception {
        mvc.perform(post(PATH).with(account(7)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(followService, never()).replace(anyLong(), anyLong(), any());
    }

    @Test
    void replaceReturnsNewList() throws Exception {
        given(followService.replace(7, 11, List.of("HGB", "WBC"))).willReturn(List.of(
                new FollowedMetricView("HGB", "血红蛋白", 0),
                new FollowedMetricView("WBC", "白细胞计数", 1)));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(PATH).session(session).header("X-CSRF-TOKEN", token).with(account(7))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].metricCode").value("HGB"))
                .andExpect(jsonPath("$[1].sortOrder").value(1));
    }

    @Test
    void replaceOtherProfileIs404() throws Exception {
        given(followService.replace(eq(7L), eq(12L), any())).willThrow(new ApiException(ErrorCode.PROFILE_NOT_FOUND));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post("/api/v1/patient-profiles/12/followed-metrics").session(session)
                        .header("X-CSRF-TOKEN", token).with(account(7))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void replaceValidationIs400() throws Exception {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        given(followService.replace(eq(7L), eq(11L), any())).willThrow(new ApiException(code, code.defaultMessage(),
                List.of(new FieldErrorItem("metricCodes", "Duplicate", "指标重复：WBC"))));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(PATH).session(session).header("X-CSRF-TOKEN", token).with(account(7))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"metricCodes\":[\"WBC\",\"WBC\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("metricCodes"));
    }
}
