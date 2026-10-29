package com.healthtrace.profile.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.indicator.domain.FollowPolicy;
import com.healthtrace.indicator.mapper.FollowedMetricMapper;
import com.healthtrace.profile.domain.AccessStatus;
import com.healthtrace.profile.domain.PatientProfile;
import com.healthtrace.profile.domain.ProfileAccess;
import com.healthtrace.profile.domain.ProfilePolicy;
import com.healthtrace.profile.domain.ProfileRecord;
import com.healthtrace.profile.domain.ProfileStatus;
import com.healthtrace.profile.domain.Relationship;
import com.healthtrace.profile.mapper.ProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** ProfileService 单元测试（TC-003：未授权不能建档、一账号一档、声明记录版本与时间）。 */
class ProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final long ACCOUNT_ID = 7L;

    private ProfileMapper mapper;
    private FollowedMetricMapper followedMetricMapper;
    private ProfileService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ProfileMapper.class);
        followedMetricMapper = mock(FollowedMetricMapper.class);
        service = new ProfileService(mapper, followedMetricMapper,
                mock(com.healthtrace.rights.consent.ConsentService.class),
                mock(com.healthtrace.rights.audit.AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        willAnswer(inv -> {
            inv.<PatientProfile>getArgument(0).setId(100L);
            return 1;
        }).given(mapper).insertProfile(any());
    }

    private static ApiException apiError(Throwable t) {
        return (ApiException) t;
    }

    @Test
    void undeclaredAuthorizationIsRejectedAndNothingIsWritten() {
        assertThatThrownBy(() -> service.createProfile(ACCOUNT_ID, Relationship.CHILD, "妈妈", false))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting("field").containsExactly("authorizationDeclared");
                });
        verify(mapper, never()).insertProfile(any());
        verify(mapper, never()).insertAccess(any());
        verify(followedMetricMapper, never()).insertAll(anyLong(), any(), any());
    }

    @Test
    void newProfileFollowsDefaultSixMetrics() {
        service.createProfile(ACCOUNT_ID, Relationship.SELF, null, true);

        verify(followedMetricMapper).insertAll(100L, List.of("WBC", "HGB", "PLT", "ALT", "AST", "CREA"), NOW_UTC);
        assertThat(FollowPolicy.DEFAULT_CODES).containsExactly("WBC", "HGB", "PLT", "ALT", "AST", "CREA");
    }

    @Test
    void selfAlsoRequiresDeclaration() {
        assertThatThrownBy(() -> service.createProfile(ACCOUNT_ID, Relationship.SELF, null, false))
                .isInstanceOf(ApiException.class);
        verify(mapper, never()).insertProfile(any());
    }

    @Test
    void missingRelationshipIsRejected() {
        assertThatThrownBy(() -> service.createProfile(ACCOUNT_ID, null, null, true))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getFieldErrors()).extracting("field").containsExactly("relationship"));
    }

    @Test
    void secondProfileIsRejectedByPreCheck() {
        given(mapper.findActiveByAccountId(ACCOUNT_ID)).willReturn(new ProfileRecord());

        assertThatThrownBy(() -> service.createProfile(ACCOUNT_ID, Relationship.SELF, null, true))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPERATION_NOT_ALLOWED));
        verify(mapper, never()).insertProfile(any());
    }

    @Test
    void concurrentDuplicateIsMappedToConflict() {
        willThrow(new DuplicateKeyException("uk_profile_access_active_account")).given(mapper).insertAccess(any());

        assertThatThrownBy(() -> service.createProfile(ACCOUNT_ID, Relationship.SELF, null, true))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPERATION_NOT_ALLOWED));
    }

    @Test
    void serverRecordsDeclarationTimeAndVersion() {
        ProfileView view = service.createProfile(ACCOUNT_ID, Relationship.CHILD, "  妈妈  ", true);

        ArgumentCaptor<ProfileAccess> captor = ArgumentCaptor.forClass(ProfileAccess.class);
        verify(mapper).insertAccess(captor.capture());
        ProfileAccess access = captor.getValue();
        assertThat(access.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(access.getProfileId()).isEqualTo(100L);
        assertThat(access.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(access.getAuthorizationDeclaredAt()).isEqualTo(NOW_UTC);
        assertThat(access.getDeclarationVersion()).isEqualTo(ProfilePolicy.DECLARATION_VERSION);

        assertThat(view.id()).isEqualTo(100L);
        assertThat(view.nickname()).isEqualTo("妈妈");
        assertThat(view.status()).isEqualTo(ProfileStatus.ACTIVE);
        assertThat(view.authorizationDeclaredAt()).isEqualTo(NOW_UTC);
        assertThat(view.declarationVersion()).isEqualTo("PROFILE-DECL-2026-10");
    }

    @Test
    void blankNicknameBecomesNullAndOverlongIsRejected() {
        assertThat(ProfileService.normalizeNickname("   ")).isNull();
        assertThat(ProfileService.normalizeNickname("😀".repeat(80))).hasSize(160);
        assertThatThrownBy(() -> ProfileService.normalizeNickname("a".repeat(81)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getFieldErrors()).extracting("field").containsExactly("nickname"));
    }

    /** relationship 只影响文案：任何关系都只能访问自己持有 ACTIVE 授权的档案。 */
    @ParameterizedTest
    @EnumSource(Relationship.class)
    void relationshipDoesNotGrantAccessToOthersProfiles(Relationship relationship) {
        service.createProfile(ACCOUNT_ID, relationship, null, true);
        given(mapper.findActiveByAccountAndProfile(anyLong(), anyLong())).willReturn(null);

        assertThatThrownBy(() -> service.getProfile(ACCOUNT_ID, 999L))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND));
    }

    @Test
    void currentProfileMissingIs404() {
        assertThatThrownBy(() -> service.getCurrentProfile(ACCOUNT_ID))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND));
    }
}
