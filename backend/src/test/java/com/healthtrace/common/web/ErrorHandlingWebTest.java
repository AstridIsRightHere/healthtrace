package com.healthtrace.common.web;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 框架级异常（404/405/415）走统一错误结构而非 500；traceId 响应头与错误体一致（含 Security 输出的 401）。 */
@WebMvcTest(controllers = {CsrfController.class, ErrorHandlingWebTest.ProbeController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ClockConfig.class, ErrorHandlingWebTest.ProbeController.class})
@TestPropertySource(properties = {
        "SPRING_PROFILES_ACTIVE=test",
        "healthtrace.security.email-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "healthtrace.security.email-index-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
class ErrorHandlingWebTest {

    private static final String PROBE = "/api/v1/test-probe";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    /** 仅测试用的接口：GET 与只接受 JSON 的 POST。 */
    @RestController
    static class ProbeController {
        @GetMapping(PROBE)
        Map<String, String> get() {
            return Map.of("ok", "1");
        }

        @PostMapping(value = PROBE, consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> post(@RequestBody Map<String, String> body) {
            return body;
        }
    }

    private static RequestPostProcessor account(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new AccountPrincipal(id), null, List.of()));
    }

    private void assertTraceIdMatches(MvcResult result) throws Exception {
        String header = result.getResponse().getHeader(TraceIdFilter.HEADER);
        JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(header).matches("[0-9a-f]{32}");
        assertThat(body.get("traceId").asString()).isEqualTo(header);
    }

    @Test
    void unknownPathIsEndpointNotFoundForLoggedInUser() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/no-such-endpoint").with(account(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-1005"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn();
        assertTraceIdMatches(result);
    }

    @Test
    void unknownPathRequiresLoginFirst() throws Exception {
        mvc.perform(get("/api/v1/no-such-endpoint"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"));
    }

    @Test
    void unsupportedMethodIs405WithAllowHeader() throws Exception {
        MvcResult result = mvc.perform(delete(PROBE).with(account(7)).with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("HT-1006"))
                .andExpect(header().exists("Allow"))
                .andReturn();
        assertThat(result.getResponse().getHeader("Allow")).contains("GET").contains("POST");
        assertTraceIdMatches(result);
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        MvcResult result = mvc.perform(post(PROBE).with(account(7)).with(csrf())
                        .contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("HT-1007"))
                .andReturn();
        assertTraceIdMatches(result);
    }

    @Test
    void unauthenticatedResponseCarriesSameTraceId() throws Exception {
        MvcResult result = mvc.perform(get(PROBE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HT-2001"))
                .andReturn();
        assertTraceIdMatches(result);
    }

    @Test
    void successfulResponseAlsoHasTraceIdHeader() throws Exception {
        String first = mvc.perform(get(PROBE).with(account(7)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(TraceIdFilter.HEADER);
        String second = mvc.perform(get(PROBE).with(account(7)))
                .andReturn().getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(first).matches("[0-9a-f]{32}");
        assertThat(second).isNotEqualTo(first);
    }
}
