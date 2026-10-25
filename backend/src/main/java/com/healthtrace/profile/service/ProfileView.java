package com.healthtrace.profile.service;

import com.healthtrace.profile.domain.ProfileRecord;
import com.healthtrace.profile.domain.ProfileStatus;
import com.healthtrace.profile.domain.Relationship;

import java.time.LocalDateTime;

/**
 * 返回给持有有效授权账号的档案视图。relationship 仅用于文案展示，不代表权限。
 * 时间为 UTC。
 */
public record ProfileView(long id, String nickname, Relationship relationship, ProfileStatus status, long version,
                          LocalDateTime authorizationDeclaredAt, String declarationVersion) {

    static ProfileView from(ProfileRecord record) {
        return new ProfileView(record.getProfileId(), record.getNickname(), record.getRelationship(),
                record.getStatus(), record.getVersion(), record.getAuthorizationDeclaredAt(),
                record.getDeclarationVersion());
    }
}
