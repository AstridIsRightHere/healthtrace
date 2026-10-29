package com.healthtrace.indicator.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.indicator.domain.FollowedMetric;
import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.mapper.FollowedMetricMapper;
import com.healthtrace.indicator.mapper.TrendMapper;
import com.healthtrace.indicator.service.FollowViews.FollowedMetricView;
import com.healthtrace.indicator.service.FollowViews.MetricDefinitionView;
import com.healthtrace.profile.domain.ProfileRecord;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 关注指标服务：越权 404、整表替换顺序、校验（空值、上限、重复、未知 code）、空列表、字典顺序。 */
class FollowServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T10:00:00.789Z"), ZoneOffset.UTC);
    private static final long ACCOUNT = 7L;
    private static final long PROFILE = 11L;

    private FollowedMetricMapper followedMetricMapper;
    private TrendMapper trendMapper;
    private ProfileMapper profileMapper;
    private AuditService auditService;
    private FollowService service;

    @BeforeEach
    void setUp() {
        followedMetricMapper = mock(FollowedMetricMapper.class);
        trendMapper = mock(TrendMapper.class);
        profileMapper = mock(ProfileMapper.class);
        auditService = mock(AuditService.class);
        service = new FollowService(followedMetricMapper, trendMapper, profileMapper, auditService, CLOCK);
        given(profileMapper.findActiveByAccountAndProfile(ACCOUNT, PROFILE)).willReturn(new ProfileRecord());
        given(trendMapper.findAllDefinitions()).willReturn(definitions("WBC", "HGB", "PLT", "ALT", "AST", "CREA"));
    }

    private static List<MetricDefinition> definitions(String... codes) {
        List<MetricDefinition> list = new ArrayList<>();
        for (String code : codes) {
            MetricDefinition d = new MetricDefinition();
            d.setCode(code);
            d.setStandardName(code + "名称");
            list.add(d);
        }
        return list;
    }

    private static FollowedMetric row(String code, int order) {
        FollowedMetric m = new FollowedMetric();
        m.setMetricCode(code);
        m.setStandardName(code + "名称");
        m.setSortOrder(order);
        return m;
    }

    private void assertValidation(List<String> codes, String reason) {
        assertThatThrownBy(() -> service.replace(ACCOUNT, PROFILE, codes))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).singleElement().satisfies(f -> {
                        assertThat(f.field()).isEqualTo("metricCodes");
                        assertThat(f.reasonCode()).isEqualTo(reason);
                    });
                });
        verify(followedMetricMapper, never()).deleteByProfile(anyLong());
        verify(followedMetricMapper, never()).insertAll(anyLong(), anyList(), any());
    }

    @Test
    void listMapsRows() {
        given(followedMetricMapper.findByProfile(PROFILE)).willReturn(List.of(row("HGB", 0), row("WBC", 1)));
        assertThat(service.list(ACCOUNT, PROFILE)).containsExactly(
                new FollowedMetricView("HGB", "HGB名称", 0), new FollowedMetricView("WBC", "WBC名称", 1));
    }

    @Test
    void listForeignProfileIs404() {
        assertThatThrownBy(() -> service.list(ACCOUNT, 99L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND));
        verify(followedMetricMapper, never()).findByProfile(anyLong());
    }

    @Test
    void replaceForeignProfileIs404AndWritesNothing() {
        assertThatThrownBy(() -> service.replace(ACCOUNT, 99L, List.of("WBC")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND));
        verify(followedMetricMapper, never()).deleteByProfile(anyLong());
        verify(auditService, never()).success(anyLong(), any(), any());
    }

    @Test
    void replaceDeletesThenInsertsInGivenOrderAndAudits() {
        given(followedMetricMapper.findByProfile(PROFILE)).willReturn(List.of(row("HGB", 0), row("WBC", 1)));
        List<FollowedMetricView> result = service.replace(ACCOUNT, PROFILE, List.of("HGB", "WBC"));

        var order = inOrder(followedMetricMapper);
        order.verify(followedMetricMapper).deleteByProfile(PROFILE);
        order.verify(followedMetricMapper).insertAll(PROFILE, List.of("HGB", "WBC"),
                LocalDateTime.of(2026, 10, 25, 10, 0, 0));
        verify(auditService).success(ACCOUNT, AuditAction.METRIC_FOLLOW_UPDATE, AuditService.token("profile", PROFILE));
        assertThat(result).extracting(FollowedMetricView::metricCode).containsExactly("HGB", "WBC");
    }

    @Test
    void emptyListUnfollowsAllWithoutInsert() {
        given(followedMetricMapper.findByProfile(PROFILE)).willReturn(List.of());
        assertThat(service.replace(ACCOUNT, PROFILE, List.of())).isEmpty();
        verify(followedMetricMapper).deleteByProfile(PROFILE);
        verify(followedMetricMapper, never()).insertAll(anyLong(), anyList(), any());
    }

    @Test
    void nullListIs400() {
        assertValidation(null, "NotNull");
    }

    @Test
    void moreThanTwentyIs400() {
        assertValidation(Collections.nCopies(21, "WBC"), "Size");
    }

    @Test
    void duplicateIs400() {
        assertValidation(List.of("WBC", "HGB", "WBC"), "Duplicate");
    }

    @Test
    void unknownCodeIs400() {
        assertValidation(List.of("WBC", "XYZ"), "UnknownMetric");
    }

    @Test
    void nullElementIs400() {
        assertValidation(Arrays.asList("WBC", null), "UnknownMetric");
    }

    @Test
    void definitionsKeepDictionaryOrder() {
        assertThat(service.listDefinitions()).extracting(MetricDefinitionView::code)
                .containsExactly("WBC", "HGB", "PLT", "ALT", "AST", "CREA");
        assertThat(service.listDefinitions().get(0).name()).isEqualTo("WBC名称");
    }
}
