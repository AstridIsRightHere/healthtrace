-- V13：临时指标（FR-009，docs/19 TEMP_METRIC，DEC-015）。
-- 默认不建临时指标，只在用户对未映射标准字典的结果主动“设为临时指标”并确认后，于本档案内创建。
-- 归并键 = 归一后的报告原名（name_key）+ 归一后的单位写法（unit_key，无单位为空串）：
-- 同档案同键自动合并为一个临时指标；跨档案永不合并（唯一键含 profile_id）。
-- 键使用 utf8mb4_0900_bin（逐码点、NO PAD）精确比较，与服务层 TrendService.normalizeName / UnitNormalizer 结果一致。
-- 所有 DATETIME 均为 UTC；ADR-009：已执行迁移不原地修改。

CREATE TABLE temp_metric
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    profile_id            BIGINT       NOT NULL,
    -- 展示名：首次创建时结果的报告原名（去掉前导标记）
    display_name          VARCHAR(160) NOT NULL,
    name_key              VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    -- 单位写法归一可能插入 ^（上标转写），比原始 unit_text(80) 略长，故留 100
    unit_key              VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '',
    -- 展示单位（首次创建时的原始写法），无单位为空
    unit_text             VARCHAR(80)  NULL,
    -- 发起创建的结果行；不建外键，报告彻底删除后该行可能已不存在
    origin_observation_id BIGINT       NULL,
    mapping_status        ENUM ('UNMERGED', 'MERGED', 'SEPARATE') NOT NULL DEFAULT 'UNMERGED',
    created_at            DATETIME     NOT NULL,
    updated_at            DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_temp_metric_key UNIQUE (profile_id, name_key, unit_key),
    CONSTRAINT fk_temp_metric_profile FOREIGN KEY (profile_id) REFERENCES patient_profile (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- V4 已预留 observation.temp_metric_id 与互斥约束 ck_observation_metric_exclusive，这里补外键
ALTER TABLE observation
    ADD CONSTRAINT fk_observation_temp_metric FOREIGN KEY (temp_metric_id) REFERENCES temp_metric (id);

-- 关注列表可关注标准指标或本档案的临时指标：改为代理主键，两种目标恰好其一非空，各自在档案内唯一。
-- 已有行全部为标准指标，原样保留（id 自动编号）。
ALTER TABLE followed_metric
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (id),
    MODIFY COLUMN metric_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN temp_metric_id BIGINT NULL AFTER metric_code,
    ADD CONSTRAINT uk_followed_metric_code UNIQUE (profile_id, metric_code),
    ADD CONSTRAINT uk_followed_metric_temp UNIQUE (profile_id, temp_metric_id),
    ADD CONSTRAINT fk_followed_metric_temp FOREIGN KEY (temp_metric_id) REFERENCES temp_metric (id),
    ADD CONSTRAINT ck_followed_metric_target CHECK ((metric_code IS NULL) <> (temp_metric_id IS NULL));
