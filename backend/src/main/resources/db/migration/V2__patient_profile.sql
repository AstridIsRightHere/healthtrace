-- V2：患者档案与账号-档案授权关系（FR-003，US-003，docs/19 PATIENT_PROFILE / PROFILE_ACCESS）
-- 所有 DATETIME 均为 UTC，由应用写入；ADR-009：已执行迁移不原地修改。

CREATE TABLE patient_profile (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    -- 患者昵称，可空；不要求真实姓名
    nickname   VARCHAR(80) NULL,
    status     ENUM ('ACTIVE', 'AUTH_SUSPENDED', 'DELETING') NOT NULL,
    -- 并发修改版本（乐观锁），新建为 0
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL,
    updated_at DATETIME    NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE profile_access (
    id                        BIGINT      NOT NULL AUTO_INCREMENT,
    account_id                BIGINT      NOT NULL,
    profile_id                BIGINT      NOT NULL,
    -- 关系画像只影响文案，不参与任何权限判断（FR-003）
    relationship              ENUM ('SELF', 'CHILD', 'SPOUSE', 'OTHER') NOT NULL,
    access_status             ENUM ('ACTIVE', 'REVOKED', 'FROZEN') NOT NULL,
    -- 操作者声明已获授权（本人为成年自记声明）的时间与声明文本版本；记录主张，不等于核验结果
    authorization_declared_at DATETIME    NULL,
    declaration_version       VARCHAR(40) NULL,
    revoked_at                DATETIME    NULL,
    created_at                DATETIME    NOT NULL,
    updated_at                DATETIME    NOT NULL,
    -- MVP 一账号一个有效档案（docs/06）：只有 ACTIVE 授权占用唯一键，撤回/冻结的历史记录不受限；
    -- 数据模型仍是多对多，未来多档需另行设计授权后再移除此约束
    active_account_id         BIGINT GENERATED ALWAYS AS (IF(access_status = 'ACTIVE', account_id, NULL)) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_profile_access_active_account UNIQUE (active_account_id),
    CONSTRAINT fk_profile_access_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_profile_access_profile FOREIGN KEY (profile_id) REFERENCES patient_profile (id),
    INDEX idx_profile_access_account (account_id, access_status),
    INDEX idx_profile_access_profile (profile_id, access_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
