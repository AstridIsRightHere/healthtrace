-- V1：账号与认证基础表（FR-001/FR-002，docs/19 ACCOUNT / INVITATION / AUTH_TOKEN）
-- 所有 DATETIME 均为 UTC，由应用写入（docs/19 通用规则）；ADR-009：已执行迁移不原地修改。

CREATE TABLE account (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    -- docs/19 email：加密存储。AES-256-GCM 载荷 = 12 字节 IV + 密文 + 16 字节标签；明文上限 254 字符
    email_ciphertext  VARBINARY(1100) NOT NULL,
    -- 受保护规范化索引：规范化邮箱的 HMAC-SHA256（十六进制），用于检索与唯一约束
    email_index       CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    email_verified_at DATETIME     NULL,
    status            ENUM ('UNVERIFIED', 'ACTIVE', 'FROZEN', 'CLOSING', 'CLOSED') NOT NULL,
    closed_at         DATETIME     NULL,
    -- DEC-017 登录失败锁定：连续失败次数与锁定截止时间（docs/19 未列出，实现所需的补充字段）
    failed_login_count INT         NOT NULL DEFAULT 0,
    locked_until      DATETIME     NULL,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_account_email_index UNIQUE (email_index)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE invitation (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    issuer_account_id BIGINT       NOT NULL,
    -- 邀请码只存 SHA-256 摘要，不保存原码
    token_digest      VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at        DATETIME     NULL,
    used_at           DATETIME     NULL,
    used_by           BIGINT       NULL,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_invitation_token_digest UNIQUE (token_digest),
    CONSTRAINT fk_invitation_issuer FOREIGN KEY (issuer_account_id) REFERENCES account (id),
    CONSTRAINT fk_invitation_used_by FOREIGN KEY (used_by) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE auth_token (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    account_id   BIGINT       NOT NULL,
    purpose      ENUM ('EMAIL_VERIFICATION', 'PASSWORD_RESET') NOT NULL,
    -- 随机令牌只存 SHA-256 摘要，不保存原令牌
    token_digest VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at   DATETIME     NOT NULL,
    consumed_at  DATETIME     NULL,
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_token_digest UNIQUE (token_digest),
    CONSTRAINT fk_auth_token_account FOREIGN KEY (account_id) REFERENCES account (id),
    INDEX idx_auth_token_account_purpose (account_id, purpose, consumed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
