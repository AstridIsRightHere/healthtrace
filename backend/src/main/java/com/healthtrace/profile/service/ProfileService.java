package com.healthtrace.profile.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.profile.domain.AccessStatus;
import com.healthtrace.profile.domain.PatientProfile;
import com.healthtrace.profile.domain.ProfileAccess;
import com.healthtrace.profile.domain.ProfilePolicy;
import com.healthtrace.profile.domain.ProfileRecord;
import com.healthtrace.profile.domain.ProfileStatus;
import com.healthtrace.profile.domain.Relationship;
import com.healthtrace.profile.mapper.ProfileMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 患者档案建立与读取（FR-003，US-003）。
 * <p>
 * 访问控制只看“账号是否持有该档案的 ACTIVE 授权”，relationship 不参与任何权限判断。
 * MVP 一账号一个有效档案：应用层预检 + 数据库 uk_profile_access_active_account 兜底并发。
 */
@Service
public class ProfileService {

    private final ProfileMapper profileMapper;
    private final Clock clock;

    public ProfileService(ProfileMapper profileMapper, Clock clock) {
        this.profileMapper = profileMapper;
        this.clock = clock;
    }

    /**
     * 建档。授权声明时间与版本由服务端写入，客户端只能表达“已勾选”。
     */
    @Transactional
    public ProfileView createProfile(long accountId, Relationship relationship, String nickname,
                                     boolean authorizationDeclared) {
        if (relationship == null) {
            throw validation("relationship", "NotNull", "请选择与患者的关系");
        }
        if (!authorizationDeclared) {
            throw validation("authorizationDeclared", "AssertTrue", "请先确认授权声明");
        }
        String normalizedNickname = normalizeNickname(nickname);

        if (profileMapper.findActiveByAccountId(accountId) != null) {
            throw alreadyHasProfile();
        }

        // DATETIME 列只保存到秒：截断后响应与库内值一致
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        PatientProfile profile = new PatientProfile();
        profile.setNickname(normalizedNickname);
        profile.setStatus(ProfileStatus.ACTIVE);
        profile.setVersion(0L);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        profileMapper.insertProfile(profile);

        ProfileAccess access = new ProfileAccess();
        access.setAccountId(accountId);
        access.setProfileId(profile.getId());
        access.setRelationship(relationship);
        access.setAccessStatus(AccessStatus.ACTIVE);
        access.setAuthorizationDeclaredAt(now);
        access.setDeclarationVersion(ProfilePolicy.DECLARATION_VERSION);
        access.setCreatedAt(now);
        access.setUpdatedAt(now);
        try {
            profileMapper.insertAccess(access);
        } catch (DuplicateKeyException e) {
            // 并发建档：唯一键兜底，事务回滚连带撤销刚插入的档案
            throw alreadyHasProfile();
        }

        return new ProfileView(profile.getId(), normalizedNickname, relationship, ProfileStatus.ACTIVE, 0L,
                now, ProfilePolicy.DECLARATION_VERSION);
    }

    public ProfileView getCurrentProfile(long accountId) {
        ProfileRecord record = profileMapper.findActiveByAccountId(accountId);
        if (record == null) {
            throw new ApiException(ErrorCode.PROFILE_NOT_FOUND);
        }
        return ProfileView.from(record);
    }

    /** 他人档案与不存在的档案返回相同的 404，不泄露档案存在性。 */
    public ProfileView getProfile(long accountId, long profileId) {
        ProfileRecord record = profileMapper.findActiveByAccountAndProfile(accountId, profileId);
        if (record == null) {
            throw new ApiException(ErrorCode.PROFILE_NOT_FOUND);
        }
        return ProfileView.from(record);
    }

    static String normalizeNickname(String nickname) {
        if (nickname == null) {
            return null;
        }
        String trimmed = nickname.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > ProfilePolicy.NICKNAME_MAX_LENGTH) {
            throw validation("nickname", "Size", "昵称不能超过 " + ProfilePolicy.NICKNAME_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static ApiException alreadyHasProfile() {
        return new ApiException(ErrorCode.OPERATION_NOT_ALLOWED, "当前账号已建立档案");
    }

    private static ApiException validation(String field, String reason, String message) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return new ApiException(code, code.defaultMessage(), List.of(new FieldErrorItem(field, reason, message)));
    }
}
