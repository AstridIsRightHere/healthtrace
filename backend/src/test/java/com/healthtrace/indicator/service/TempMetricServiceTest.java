package com.healthtrace.indicator.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.indicator.domain.FollowPolicy;
import com.healthtrace.indicator.domain.TempMetric;
import com.healthtrace.indicator.domain.TempMetricCandidate;
import com.healthtrace.indicator.mapper.FollowedMetricMapper;
import com.healthtrace.indicator.mapper.TempMetricMapper;
import com.healthtrace.indicator.service.TempMetricViews.TempMetricView;
import com.healthtrace.profile.domain.ProfileRecord;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 临时指标服务（FR-009，DEC-015）：越权 404、状态校验、同键复用与归并、关注上限、删除顺序、确认后自动归入。 */
class TempMetricServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneOffset.UTC);
    private static final long ACCOUNT = 7L;
    private static final long PROFILE = 11L;

    private TempMetricMapper tempMetricMapper;
    private FollowedMetricMapper followedMetricMapper;
    private ProfileMapper profileMapper;
    private AuditService auditService;
    private TempMetricService service;

    @BeforeEach
    void setUp() {
        tempMetricMapper = mock(TempMetricMapper.class);
        followedMetricMapper = mock(FollowedMetricMapper.class);
        profileMapper = mock(ProfileMapper.class);
        auditService = mock(AuditService.class);
        service = new TempMetricService(tempMetricMapper, followedMetricMapper, profileMapper, auditService, CLOCK);
        given(profileMapper.findActiveByAccountAndProfile(ACCOUNT, PROFILE)).willReturn(new ProfileRecord());
    }

    private static TempMetricCandidate candidate(long id, String name, String unit) {
        TempMetricCandidate c = new TempMetricCandidate();
        c.setObservationId(id);
        c.setReportId(21L);
        c.setProfileId(PROFILE);
        c.setStatus(ObservationStatus.FORMAL);
        c.setProcessingStatus(ProcessingStatus.CONFIRMED);
        c.setVisibilityStatus(VisibilityStatus.NORMAL);
        c.setOriginalName(name);
        c.setUnitText(unit);
        return c;
    }

    private static TempMetric temp(long id, String nameKey, String unitKey, long count, boolean followed) {
        TempMetric t = new TempMetric();
        t.setId(id);
        t.setProfileId(PROFILE);
        t.setDisplayName("促甲状腺激素");
        t.setNameKey(nameKey);
        t.setUnitKey(unitKey);
        t.setUnitText("mIU/L");
        t.setObservationCount(count);
        t.setFollowed(followed);
        return t;
    }

    private static void assertValidation(Runnable call, String field, String reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.getFieldErrors()).singleElement().satisfies(f -> {
                assertThat(f.field()).isEqualTo(field);
                assertThat(f.reasonCode()).isEqualTo(reason);
            });
        });
    }

    @Test
    void foreignProfileIs404() {
        assertThatThrownBy(() -> service.list(ACCOUNT, 99L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND));
        assertThatThrownBy(() -> service.create(ACCOUNT, 99L, 501L, false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND));
        verify(tempMetricMapper, never()).findCandidate(anyLong());
    }

    @Test
    void observationOfOtherProfileIs404() {
        TempMetricCandidate c = candidate(501L, "促甲状腺激素", "mIU/L");
        c.setProfileId(12L);
        given(tempMetricMapper.findCandidate(501L)).willReturn(c);
        assertThatThrownBy(() -> service.create(ACCOUNT, PROFILE, 501L, false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        given(tempMetricMapper.findCandidate(502L)).willReturn(null);
        assertThatThrownBy(() -> service.create(ACCOUNT, PROFILE, 502L, false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void missingObservationIdIs400() {
        assertValidation(() -> service.create(ACCOUNT, PROFILE, null, false), "observationId", "NotNull");
    }

    @Test
    void draftRowOrUnconfirmedOrRemovedReportIsNotConfirmed() {
        TempMetricCandidate draft = candidate(501L, "促甲状腺激素", "mIU/L");
        draft.setStatus(ObservationStatus.DRAFT);
        given(tempMetricMapper.findCandidate(501L)).willReturn(draft);
        assertValidation(() -> service.create(ACCOUNT, PROFILE, 501L, false), "observationId", "NotConfirmed");

        TempMetricCandidate pending = candidate(502L, "促甲状腺激素", "mIU/L");
        pending.setProcessingStatus(ProcessingStatus.PENDING_CONFIRMATION);
        given(tempMetricMapper.findCandidate(502L)).willReturn(pending);
        assertValidation(() -> service.create(ACCOUNT, PROFILE, 502L, false), "observationId", "NotConfirmed");

        TempMetricCandidate removed = candidate(503L, "促甲状腺激素", "mIU/L");
        removed.setVisibilityStatus(VisibilityStatus.LIST_REMOVED);
        given(tempMetricMapper.findCandidate(503L)).willReturn(removed);
        assertValidation(() -> service.create(ACCOUNT, PROFILE, 503L, false), "observationId", "NotConfirmed");
        verify(tempMetricMapper, never()).insert(any());
    }

    @Test
    void standardMetricRowIsRejected() {
        TempMetricCandidate c = candidate(501L, "白细胞", "10^9/L");
        c.setMetricDefinitionId(1L);
        given(tempMetricMapper.findCandidate(501L)).willReturn(c);
        assertValidation(() -> service.create(ACCOUNT, PROFILE, 501L, false), "observationId", "StandardMetric");
    }

    @Test
    void createInsertsAndAssignsAllSameKeyRows() {
        given(tempMetricMapper.findCandidate(501L)).willReturn(candidate(501L, "★促甲状腺激素", "mIU/L"));
        given(tempMetricMapper.insert(any())).willAnswer(inv -> {
            inv.<TempMetric>getArgument(0).setId(3L);
            return 1;
        });
        // 同名（去 ★、空白）同单位写法（大小写 / 空白归一）的行归入；不同单位不归入
        given(tempMetricMapper.findUnassignedFormalByProfile(PROFILE)).willReturn(List.of(
                candidate(501L, "★促甲状腺激素", "mIU/L"),
                candidate(601L, "促甲状腺激素", "mIU/l"),
                candidate(602L, "促甲状腺激素", "uIU/mL")));
        given(tempMetricMapper.assignObservations(eq(3L), any(), any())).willReturn(2);
        given(tempMetricMapper.findById(PROFILE, 3L)).willReturn(temp(3L, "促甲状腺激素", "mIU/L", 2, false));

        TempMetricView view = service.create(ACCOUNT, PROFILE, 501L, false);

        assertThat(view).isEqualTo(new TempMetricView("TMP:3", "促甲状腺激素", "mIU/L", 2, false));
        verify(tempMetricMapper).insert(org.mockito.ArgumentMatchers.argThat(t ->
                t.getNameKey().equals("促甲状腺激素") && t.getUnitKey().equals("mIU/L")
                        && t.getDisplayName().equals("促甲状腺激素") && t.getOriginObservationId() == 501L
                        && t.getMappingStatus().equals("UNMERGED")));
        verify(tempMetricMapper).assignObservations(eq(3L), eq(List.of(501L, 601L)), any());
        verify(tempMetricMapper).markMerged(eq(3L), any());
        verify(auditService).success(ACCOUNT, AuditAction.TEMP_METRIC_CREATE, "tempMetric:3");
        verify(followedMetricMapper, never()).appendTempMetric(anyLong(), anyLong(), any());
    }

    @Test
    void sameKeyReusesExistingTempMetric() {
        given(tempMetricMapper.findCandidate(701L)).willReturn(candidate(701L, "促甲状腺激素", "mIU/L"));
        given(tempMetricMapper.findByKey(PROFILE, "促甲状腺激素", "mIU/L"))
                .willReturn(temp(3L, "促甲状腺激素", "mIU/L", 2, false));
        given(tempMetricMapper.findUnassignedFormalByProfile(PROFILE))
                .willReturn(List.of(candidate(701L, "促甲状腺激素", "mIU/L")));
        given(tempMetricMapper.findById(PROFILE, 3L)).willReturn(temp(3L, "促甲状腺激素", "mIU/L", 3, false));

        assertThat(service.create(ACCOUNT, PROFILE, 701L, false).code()).isEqualTo("TMP:3");
        verify(tempMetricMapper, never()).insert(any());
        verify(tempMetricMapper).assignObservations(eq(3L), eq(List.of(701L)), any());
    }

    @Test
    void alreadyMappedIsIdempotentAndFollowAppends() {
        TempMetricCandidate c = candidate(501L, "促甲状腺激素", "mIU/L");
        c.setTempMetricId(3L);
        given(tempMetricMapper.findCandidate(501L)).willReturn(c);
        given(tempMetricMapper.findById(PROFILE, 3L)).willReturn(temp(3L, "促甲状腺激素", "mIU/L", 2, false));
        given(followedMetricMapper.countByProfile(PROFILE)).willReturn(6);

        service.create(ACCOUNT, PROFILE, 501L, true);

        verify(tempMetricMapper, never()).insert(any());
        verify(tempMetricMapper, never()).assignObservations(anyLong(), any(), any());
        verify(followedMetricMapper).appendTempMetric(eq(PROFILE), eq(3L), any());
    }

    @Test
    void alreadyFollowedDoesNotAppendAgain() {
        TempMetricCandidate c = candidate(501L, "促甲状腺激素", "mIU/L");
        c.setTempMetricId(3L);
        given(tempMetricMapper.findCandidate(501L)).willReturn(c);
        given(tempMetricMapper.findById(PROFILE, 3L)).willReturn(temp(3L, "促甲状腺激素", "mIU/L", 2, true));

        assertThat(service.create(ACCOUNT, PROFILE, 501L, true).followed()).isTrue();
        verify(followedMetricMapper, never()).appendTempMetric(anyLong(), anyLong(), any());
    }

    @Test
    void followBeyondLimitIs400() {
        TempMetricCandidate c = candidate(501L, "促甲状腺激素", "mIU/L");
        c.setTempMetricId(3L);
        given(tempMetricMapper.findCandidate(501L)).willReturn(c);
        given(tempMetricMapper.findById(PROFILE, 3L)).willReturn(temp(3L, "促甲状腺激素", "mIU/L", 2, false));
        given(followedMetricMapper.countByProfile(PROFILE)).willReturn(FollowPolicy.MAX_FOLLOWED);

        assertValidation(() -> service.create(ACCOUNT, PROFILE, 501L, true), "follow", "Size");
        verify(followedMetricMapper, never()).appendTempMetric(anyLong(), anyLong(), any());
    }

    @Test
    void deleteRemovesFollowsThenUnmapsThenDeletes() {
        given(tempMetricMapper.findById(PROFILE, 3L)).willReturn(temp(3L, "促甲状腺激素", "mIU/L", 2, true));

        service.delete(ACCOUNT, PROFILE, 3L);

        InOrder order = inOrder(followedMetricMapper, tempMetricMapper, auditService);
        order.verify(followedMetricMapper).deleteByTempMetric(3L);
        order.verify(tempMetricMapper).unassignObservations(3L);
        order.verify(tempMetricMapper).deleteById(PROFILE, 3L);
        order.verify(auditService).success(ACCOUNT, AuditAction.TEMP_METRIC_DELETE, "tempMetric:3");
    }

    @Test
    void deleteUnknownOrForeignIs404() {
        assertThatThrownBy(() -> service.delete(ACCOUNT, PROFILE, 9L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode().status().value()).isEqualTo(404));
        verify(tempMetricMapper, never()).unassignObservations(anyLong());
    }

    @Test
    void assignReportMapsOnlyMatchingKeys() {
        given(tempMetricMapper.findUnassignedFormalByReport(21L)).willReturn(List.of(
                candidate(801L, "促甲状腺激素 ", "mIU/L"),
                candidate(802L, "其他项目", "mIU/L")));
        given(tempMetricMapper.findKeysByProfile(PROFILE))
                .willReturn(List.of(temp(3L, "促甲状腺激素", "mIU/L", 0, false)));
        given(tempMetricMapper.assignObservations(eq(3L), any(), any())).willReturn(1);

        assertThat(service.assignReport(PROFILE, 21L)).isEqualTo(1);
        verify(tempMetricMapper).assignObservations(eq(3L), eq(List.of(801L)), any());
        verify(tempMetricMapper).markMerged(eq(3L), any());
    }

    @Test
    void assignReportWithoutTempMetricsDoesNothing() {
        given(tempMetricMapper.findUnassignedFormalByReport(21L))
                .willReturn(List.of(candidate(801L, "促甲状腺激素", "mIU/L")));
        given(tempMetricMapper.findKeysByProfile(PROFILE)).willReturn(List.of());

        assertThat(service.assignReport(PROFILE, 21L)).isZero();
        verify(tempMetricMapper, never()).assignObservations(anyLong(), any(), any());
    }
}
