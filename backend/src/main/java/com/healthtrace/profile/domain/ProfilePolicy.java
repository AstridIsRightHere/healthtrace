package com.healthtrace.profile.domain;

/** 档案业务常量（FR-003）。 */
public final class ProfilePolicy {

    /** 当前授权声明文本版本，由服务端写入，不接受客户端传值。声明文案变更时递增。 */
    public static final String DECLARATION_VERSION = "PROFILE-DECL-2026-10";

    /** 昵称上限（docs/19 nickname VARCHAR(80)），按 Unicode 码点计。 */
    public static final int NICKNAME_MAX_LENGTH = 80;

    private ProfilePolicy() {
    }
}
