-- V8：档案关注指标（FR-009）。每个档案保存一份有序的关注列表，用于首页卡片与趋势入口。
-- 批量关注、取消关注、拖拽排序都以“整表替换”方式保存：删除该档案全部行后按新顺序插入。
-- metric_code 与 metric_definition.code 字符集/排序规则完全一致（ascii_bin），否则外键无法建立。
-- 所有 DATETIME 均为 UTC；ADR-009：已执行迁移不原地修改。

CREATE TABLE followed_metric
(
    profile_id  BIGINT      NOT NULL,
    metric_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- 从 0 开始的展示顺序
    sort_order  INT         NOT NULL,
    created_at  DATETIME    NOT NULL,
    PRIMARY KEY (profile_id, metric_code),
    CONSTRAINT fk_followed_metric_profile FOREIGN KEY (profile_id) REFERENCES patient_profile (id),
    CONSTRAINT fk_followed_metric_code FOREIGN KEY (metric_code) REFERENCES metric_definition (code),
    INDEX idx_followed_metric_profile_sort (profile_id, sort_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 为已有档案回填 PRD 默认关注 6 项。
-- 顺序须与 com.healthtrace.indicator.domain.FollowPolicy.DEFAULT_CODES 保持一致（新建档案由应用写入同一列表）。
INSERT INTO followed_metric (profile_id, metric_code, sort_order, created_at)
SELECT p.id, d.code, d.sort_order, UTC_TIMESTAMP()
FROM patient_profile p
         CROSS JOIN (SELECT 'WBC' AS code, 0 AS sort_order
                     UNION ALL SELECT 'HGB', 1
                     UNION ALL SELECT 'PLT', 2
                     UNION ALL SELECT 'ALT', 3
                     UNION ALL SELECT 'AST', 4
                     UNION ALL SELECT 'CREA', 5) d;
