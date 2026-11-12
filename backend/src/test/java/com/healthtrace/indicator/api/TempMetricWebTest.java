package com.healthtrace.indicator.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.indicator.service.TempMetricService;
import com.healthtrace.indicator.service.TempMetricViews.TempMetricView;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 临时指标接口（FR-009）：登录、CSRF、响应结构、201 / 204、404 与 400 映射。 */
@WebMvcTest(controllers = {TempMetricController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class TempMetricWebTest {

    private static final String PATH = "/api/v1/patient-profiles/11/temporary-metrics";
    private static final String BODY = "{\"observationId\":501,\"follow\":true}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TempMetricService tempMetricService;

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
    void listReturnsShape() throws Exception {
        given(tempMetricService.list(7, 11)).willReturn(List.of(
                new TempMetricView("TMP:3", "促甲状腺激素", "mIU/L", 2, true),
                new TempMetricView("TMP:4", "某定性项目", null, 1, false)));
        mvc.perform(get(PATH).with(account(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("TMP:3"))
                .andExpect(jsonPath("$[0].name").value("促甲状腺激素"))
                .andExpect(jsonPath("$[0].unit").value("mIU/L"))
                .andExpect(jsonPath("$[0].observationCount").value(2))
                .andExpect(jsonPath("$[0].followed").value(true))
                .andExpect(jsonPath("$[1].unit").doesNotExist())
                .andExpect(jsonPath("$[1].followed").value(false));
    }

    @Test
    void listOtherProfileIs404() throws Exception {
        given(tempMetricService.list(7, 12)).willThrow(new ApiException(ErrorCode.PROFILE_NOT_FOUND));
        mvc.perform(get("/api/v1/patient-profiles/12/temporary-metrics").with(account(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void createRequiresCsrf() throws Exception {
        mvc.perform(post(PATH).with(account(7)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(tempMetricService, never()).create(anyLong(), anyLong(), any(), any());
    }

    @Test
    void createReturns201() throws Exception {
        given(tempMetricService.create(7, 11, 501L, true))
                .willReturn(new TempMetricView("TMP:3", "促甲状腺激素", "mIU/L", 2, true));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(PATH).session(session).with(account(7)).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("TMP:3"))
                .andExpect(jsonPath("$.observationCount").value(2))
                .andExpect(jsonPath("$.followed").value(true));
    }

    @Test
    void createNotConfirmedIs400() throws Exception {
        given(tempMetricService.create(7, 11, 501L, true)).willThrow(new ApiException(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new FieldErrorItem("observationId", "NotConfirmed", "只有已确认报告中的结果可以设为临时指标"))));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(PATH).session(session).with(account(7)).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("observationId"))
                .andExpect(jsonPath("$.fieldErrors[0].reasonCode").value("NotConfirmed"));
    }

    @Test
    void createForeignObservationIs404() throws Exception {
        given(tempMetricService.create(7, 11, 501L, true)).willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(PATH).session(session).with(account(7)).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void deleteReturns204() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(delete(PATH + "/3").session(session).with(account(7)).header("X-CSRF-TOKEN", token))
                .andExpect(status().isNoContent());
        verify(tempMetricService).delete(7, 11, 3);
    }

    @Test
    void deleteUnknownIs404() throws Exception {
        willThrow(new ApiException(ErrorCode.TEMP_METRIC_NOT_FOUND)).given(tempMetricService).delete(7, 11, 9);
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(delete(PATH + "/9").session(session).with(account(7)).header("X-CSRF-TOKEN", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }
}
