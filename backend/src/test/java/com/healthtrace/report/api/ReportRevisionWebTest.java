package com.healthtrace.report.api;

import com.healthtrace.account.api.CsrfController;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.common.ClockConfig;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.service.ReportRevisionService;
import com.healthtrace.report.service.RevisionViews.ChangeType;
import com.healthtrace.report.service.RevisionViews.CreateRevisionCommand;
import com.healthtrace.report.service.RevisionViews.ObservationSnapshot;
import com.healthtrace.report.service.RevisionViews.ObservationVersionView;
import com.healthtrace.report.service.RevisionViews.OperationType;
import com.healthtrace.report.service.RevisionViews.ReviseResult;
import com.healthtrace.report.service.RevisionViews.RevisionChange;
import com.healthtrace.report.service.RevisionViews.RevisionView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 已确认报告指标级修订（FR-014）接口：未登录 401、无 CSRF 403、越权 404、校验 400、版本冲突 409、
 * 创建 201 + Location、修改记录与指标版本链读取；旧的整表 PATCH 已下线。
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
    private static final String URL = "/api/v1/reports/21/revisions";
    private static final String BODY = """
            {"version":3,"reason":"核对纸质报告","examinationDate":"2026-10-20",
             "operations":[
               {"op":"ADD","observation":{"originalName":"血小板","originalValue":"210","unitText":"10^9/L"}},
               {"op":"UPDATE","observationId":12,
                "observation":{"originalName":"血红蛋白","originalValue":"150","unitText":"g/L"}},
               {"op":"DELETE","observationId":13}]}
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

    private org.springframework.test.web.servlet.ResultActions postAs(long accountId, String url, String body)
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        return mvc.perform(post(url).with(account(accountId)).session(session).header("X-CSRF-TOKEN", token)
                .contentType(JSON).content(body));
    }

    @Test
    void createRequiresLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(post(URL).session(session).header("X-CSRF-TOKEN", token).contentType(JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(revisionService, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    void createRequiresCsrf() throws Exception {
        mvc.perform(post(URL).with(account(7L)).contentType(JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(revisionService, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    void createSucceedsWith201AndLocationAndParsesOperations() throws Exception {
        given(revisionService.create(eq(7L), eq(21L), any()))
                .willReturn(new ReviseResult(21L, 4L, LocalDate.of(2026, 10, 20), 99L, 4));
        postAs(7L, URL, BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/reports/21/revisions/99"))
                .andExpect(jsonPath("$.reportId").value(21))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.examinationDate").value("2026-10-20"))
                .andExpect(jsonPath("$.revisionId").value(99))
                .andExpect(jsonPath("$.changeCount").value(4));

        ArgumentCaptor<CreateRevisionCommand> captor = ArgumentCaptor.forClass(CreateRevisionCommand.class);
        verify(revisionService).create(eq(7L), eq(21L), captor.capture());
        CreateRevisionCommand cmd = captor.getValue();
        assertThat(cmd.version()).isEqualTo(3L);
        assertThat(cmd.reason()).isEqualTo("核对纸质报告");
        assertThat(cmd.examinationDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(cmd.operations()).extracting(o -> o.op())
                .containsExactly(OperationType.ADD, OperationType.UPDATE, OperationType.DELETE);
        assertThat(cmd.operations().get(1).observationId()).isEqualTo(12L);
        assertThat(cmd.operations().get(1).observation().originalValue()).isEqualTo("150");
        assertThat(cmd.operations().get(2).observation()).isNull();
    }

    @Test
    void unknownOperationTypeIsMalformed() throws Exception {
        postAs(7L, URL, """
                {"version":3,"operations":[{"op":"REPLACE","observationId":12}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1002"));
        verify(revisionService, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    void createOthersReportIsNotFound() throws Exception {
        given(revisionService.create(anyLong(), anyLong(), any()))
                .willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        postAs(7L, "/api/v1/reports/88/revisions", BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HT-3001"));
    }

    @Test
    void validationErrorsCarryOperationPaths() throws Exception {
        given(revisionService.create(anyLong(), anyLong(), any()))
                .willThrow(new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                        List.of(new FieldErrorItem("operations[1].observation.originalName", "REQUIRED", "请填写项目名称"),
                                new FieldErrorItem("operations", "LAST_OBSERVATION", "已确认的报告至少保留一项检验结果"))));
        postAs(7L, URL, BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HT-1001"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("operations[1].observation.originalName"))
                .andExpect(jsonPath("$.fieldErrors[1].reasonCode").value("LAST_OBSERVATION"));
    }

    @Test
    void notConfirmedIsStateConflict() throws Exception {
        given(revisionService.create(anyLong(), anyLong(), any()))
                .willThrow(new ApiException(ErrorCode.REPORT_STATE_CONFLICT, "只有已确认的报告可以修改"));
        postAs(7L, URL, BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HT-4002"));
    }

    @Test
    void staleVersionIsConflict() throws Exception {
        given(revisionService.create(anyLong(), anyLong(), any()))
                .willThrow(new ApiException(ErrorCode.VERSION_CONFLICT));
        postAs(7L, URL, BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HT-3002"));
    }

    @Test
    void legacyPatchEndpointIsGone() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mvc.perform(patch("/api/v1/reports/21").with(account(7L)).session(session).header("X-CSRF-TOKEN", token)
                        .contentType(JSON).content("{\"version\":3,\"observations\":[]}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
    }

    @Test
    void revisionsRequireLogin() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void revisionsOfOthersReportIsNotFound() throws Exception {
        given(revisionService.listRevisions(7L, 88L)).willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/reports/88/revisions").with(account(7L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void revisionsAreListedWithRowIds() throws Exception {
        given(revisionService.listRevisions(7L, 21L)).willReturn(List.of(new RevisionView(99L, 3L, 4L, "录入错误",
                LocalDateTime.of(2026, 10, 27, 8, 0), null,
                List.of(new RevisionChange(ChangeType.UPDATED, 12L, 30L,
                                new ObservationSnapshot("血红蛋白", "140", "g/L", null),
                                new ObservationSnapshot("血红蛋白", "150", "g/L", null)),
                        new RevisionChange(ChangeType.REMOVED, 13L, null,
                                new ObservationSnapshot("白细胞", "6.1", null, null), null)))));
        mvc.perform(get(URL).with(account(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toVersion").value(4))
                .andExpect(jsonPath("$[0].changes[0].type").value("UPDATED"))
                .andExpect(jsonPath("$[0].changes[0].observationId").value(12))
                .andExpect(jsonPath("$[0].changes[0].newObservationId").value(30))
                .andExpect(jsonPath("$[0].changes[0].before.originalValue").value("140"))
                .andExpect(jsonPath("$[0].changes[0].after.originalValue").value("150"))
                .andExpect(jsonPath("$[0].changes[1].type").value("REMOVED"))
                .andExpect(jsonPath("$[0].changes[1].newObservationId").doesNotExist());
    }

    @Test
    void observationHistoryIsListed() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 10, 27, 8, 0);
        given(revisionService.observationHistory(7L, 21L, 12L)).willReturn(List.of(
                new ObservationVersionView(30L, 12L, ObservationStatus.FORMAL, "血红蛋白", "150", "g/L", null,
                        LocalDate.of(2026, 10, 20), 5L, 99L, null, t, null),
                new ObservationVersionView(12L, null, ObservationStatus.SUPERSEDED, "血红蛋白", "140", "g/L", null,
                        LocalDate.of(2026, 10, 20), 5L, null, 99L, t.minusDays(3), t)));
        mvc.perform(get("/api/v1/reports/21/observations/12/history").with(account(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(30))
                .andExpect(jsonPath("$[0].status").value("FORMAL"))
                .andExpect(jsonPath("$[0].supersedesId").value(12))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].retiredRevisionId").value(99));
    }

    @Test
    void observationHistoryOfOthersReportIsNotFound() throws Exception {
        given(revisionService.observationHistory(7L, 88L, 12L))
                .willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/reports/88/observations/12/history").with(account(7L)))
                .andExpect(status().isNotFound());
    }
}
