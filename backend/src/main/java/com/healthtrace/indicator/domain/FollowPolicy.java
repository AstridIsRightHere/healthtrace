package com.healthtrace.indicator.domain;

import java.util.List;

/** 关注指标规则（FR-009）。 */
public final class FollowPolicy {

    /**
     * PRD 默认关注 6 项，顺序即展示顺序（sort_order 0..5）。
     * 新建档案在建档事务内写入；须与 db/migration/V8__followed_metric.sql 的回填列表保持一致。
     */
    public static final List<String> DEFAULT_CODES = List.of("WBC", "HGB", "PLT", "ALT", "AST", "CREA");

    /** 单个档案最多关注的指标数。 */
    public static final int MAX_FOLLOWED = 20;

    private FollowPolicy() {
    }
}
