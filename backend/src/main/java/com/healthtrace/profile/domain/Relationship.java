package com.healthtrace.profile.domain;

/**
 * 操作者与患者的关系画像（docs/19 PROFILE_ACCESS.relationship）。
 * 只决定界面文案与声明文本，绝不参与权限判断（FR-003，TC-003）。
 */
public enum Relationship {
    SELF,
    CHILD,
    SPOUSE,
    OTHER
}
