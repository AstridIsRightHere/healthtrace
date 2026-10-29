-- V7：合规闭环（docs/19 CONSENT_RECORD / AUDIT_EVENT / REPORT_REVISION；FR-014 修改留痕）
-- 枚举在库中以英文代码存储，展示文案由应用映射：
--   consent_record.purpose      HEALTH_DATA_STORAGE(健康资料保存) / OCR_PROCESSING(OCR处理)
--   consent_record.subject_role SELF(本人) / PROXY(代管；代管声明不等于患者本人同意)
--   consent_record.decision     GRANTED(同意) / REFUSED(拒绝) / WITHDRAWN(撤回)
--   audit_event.outcome         SUCCESS(成功) / DENIED(拒绝) / FAILED(失败)
-- 所有 DATETIME 均为 UTC，由应用写入；ADR-009：已执行迁移不原地修改。

-- 同意记录只追加不修改：撤回以新增一条 WITHDRAWN 记录表达，便于还原任一时点的授权状态。
CREATE TABLE consent_record
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    account_id     BIGINT       NOT NULL,
    profile_id     BIGINT       NOT NULL,
    purpose        VARCHAR(32)  NOT NULL,
    subject_role   VARCHAR(16)  NOT NULL,
    notice_version VARCHAR(40)  NOT NULL,
    decision       VARCHAR(16)  NOT NULL,
    -- 第三方处理范围，如 BAIDU_OCR；本地保存类同意为空
    provider_scope VARCHAR(120) NULL,
    recorded_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_consent_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_consent_profile FOREIGN KEY (profile_id) REFERENCES patient_profile (id),
    CONSTRAINT ck_consent_purpose CHECK (purpose IN ('HEALTH_DATA_STORAGE', 'OCR_PROCESSING')),
    CONSTRAINT ck_consent_role CHECK (subject_role IN ('SELF', 'PROXY')),
    CONSTRAINT ck_consent_decision CHECK (decision IN ('GRANTED', 'REFUSED', 'WITHDRAWN')),
    INDEX idx_consent_profile_purpose (profile_id, purpose, recorded_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- V4 预留的 consent_record_id 从此指向真实记录；历史任务保持为空（仅有 consent_declared_at）。
ALTER TABLE ocr_job
    ADD CONSTRAINT fk_ocr_job_consent FOREIGN KEY (consent_record_id) REFERENCES consent_record (id);

-- 审计事件只追加；不含病历正文、邮箱、令牌。resource_token 为最小关联标识（如 report:42）。
-- actor_account_id 不加外键：登录失败等场景可为空，账号注销后需最小化而不能被级联删除。
CREATE TABLE audit_event
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    actor_account_id BIGINT       NULL,
    action           VARCHAR(80)  NOT NULL,
    resource_token   VARCHAR(128) NULL,
    outcome          VARCHAR(16)  NOT NULL,
    occurred_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    INDEX idx_audit_actor_time (actor_account_id, occurred_at),
    INDEX idx_audit_resource (resource_token, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 正式报告修改记录（FR-014）：change_payload 为前后差异 JSON，经字段加密后存储（含检验结果）。
-- 原图与 OCR 原文不被修改；正式 observation 行按差异更新/新增/删除，旧值只保存在本表。
CREATE TABLE report_revision
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    report_id        BIGINT       NOT NULL,
    actor_account_id BIGINT       NOT NULL,
    from_version     BIGINT       NOT NULL,
    to_version       BIGINT       NOT NULL,
    change_payload   MEDIUMBLOB   NOT NULL,
    reason           VARCHAR(240) NULL,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_revision_report FOREIGN KEY (report_id) REFERENCES report (id),
    CONSTRAINT fk_revision_actor FOREIGN KEY (actor_account_id) REFERENCES account (id),
    UNIQUE KEY uk_revision_report_version (report_id, to_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
