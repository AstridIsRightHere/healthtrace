package com.healthtrace.profile.mapper;

import com.healthtrace.profile.domain.PatientProfile;
import com.healthtrace.profile.domain.ProfileAccess;
import com.healthtrace.profile.domain.ProfileRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 档案与授权关系持久化。所有读取都以“账号持有 ACTIVE 授权”为前提，避免越权读取。 */
@Mapper
public interface ProfileMapper {

    int insertProfile(PatientProfile profile);

    /** 违反 uk_profile_access_active_account（账号已有有效档案）时抛 DuplicateKeyException。 */
    int insertAccess(ProfileAccess access);

    ProfileRecord findActiveByAccountId(@Param("accountId") long accountId);

    ProfileRecord findActiveByAccountAndProfile(@Param("accountId") long accountId,
                                                @Param("profileId") long profileId);
}
