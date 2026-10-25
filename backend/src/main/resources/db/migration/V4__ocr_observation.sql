-- V4：OCR 任务与观测值草稿（FR-006/FR-007/FR-014/FR-015，docs/19 OCR_JOB / OBSERVATION）
-- 所有 DATETIME 均为 UTC，由应用写入；ADR-009：已执行迁移不原地修改。

CREATE TABLE ocr_job (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    report_id                 BIGINT       NOT NULL,
    -- OCR 供应商标识，如 baidu-medical-report
    provider                  VARCHAR(80)  NOT NULL,
    -- CONSENT_RECORD 尚未实现：暂为空且不加外键；本次同意时间记录在 consent_declared_at
    consent_record_id         BIGINT       NULL,
    -- 用户在发起识别时明确勾选第三方 OCR 同意的时间（NFR-002）
    consent_declared_at       DATETIME     NOT NULL,
    -- 发起账号（审计用，不参与权限判断）
    created_by                BIGINT       NOT NULL,
    status                    ENUM ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED') NOT NULL,
    attempt_count             INT          NOT NULL DEFAULT 0,
    provider_request_id       VARCHAR(160) NULL,
    -- 原始识别文本与原始结构化结果：AES-256-GCM 加密（HT_OCR_ENCRYPTION_KEY），FR-014 保留原文
    raw_text_ciphertext       MEDIUMBLOB   NULL,
    raw_extraction_ciphertext MEDIUMBLOB   NULL,
    -- 稳定错误码（不含供应商原始消息与请求内容）
    error_code                VARCHAR(80)  NULL,
    next_retry_at             DATETIME     NULL,
    started_at                DATETIME     NULL,
    finished_at               DATETIME     NULL,
    created_at                DATETIME     NOT NULL,
    updated_at                DATETIME     NOT NULL,
    -- 同一报告同时最多一个等待/运行中的任务：重复提交与并发创建由唯一约束兜底（NFR-004 幂等）
    active_report_id          BIGINT GENERATED ALWAYS AS (IF(status IN ('PENDING', 'RUNNING'), report_id, NULL)) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_ocr_job_active_report UNIQUE (active_report_id),
    CONSTRAINT fk_ocr_job_report FOREIGN KEY (report_id) REFERENCES report (id),
    CONSTRAINT fk_ocr_job_created_by FOREIGN KEY (created_by) REFERENCES account (id),
    INDEX idx_ocr_job_report (report_id, id),
    INDEX idx_ocr_job_status_retry (status, next_retry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE observation (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    report_id            BIGINT        NOT NULL,
    -- 指标字典（FR-008）与临时指标尚未实现：暂不加外键，二者互斥
    metric_definition_id BIGINT        NULL,
    temp_metric_id       BIGINT        NULL,
    -- 报告单上的原始写法，始终保留
    original_name        VARCHAR(160)  NOT NULL,
    original_value       VARCHAR(160)  NOT NULL,
    result_kind          ENUM ('EXACT', 'LESS_THAN', 'GREATER_THAN', 'QUALITATIVE', 'UNKNOWN') NOT NULL,
    numeric_value        DECIMAL(20, 8) NULL,
    unit_text            VARCHAR(80)   NULL,
    reference_text       VARCHAR(160)  NULL,
    reference_low        DECIMAL(20, 8) NULL,
    reference_high       DECIMAL(20, 8) NULL,
    abnormal_flag        ENUM ('HIGH', 'LOW', 'NORMAL', 'UNKNOWN') NULL,
    -- 草稿可为空，确认时必填（一份报告一个检查日期）
    examination_date     DATE          NULL,
    specimen_text        VARCHAR(120)  NULL,
    method_text          VARCHAR(120)  NULL,
    -- 供应商真实返回的置信度；未返回为 NULL，绝不伪造（FR-007）
    confidence           DECIMAL(6, 5) NULL,
    confirmed_at         DATETIME      NULL,
    source_photo_id      BIGINT        NULL,
    status               ENUM ('DRAFT', 'FORMAL', 'DELETING') NOT NULL,
    -- 报告单内的展示顺序
    row_order            INT           NOT NULL,
    created_at           DATETIME      NOT NULL,
    updated_at           DATETIME      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_observation_metric_exclusive CHECK (metric_definition_id IS NULL OR temp_metric_id IS NULL),
    CONSTRAINT fk_observation_report FOREIGN KEY (report_id) REFERENCES report (id),
    CONSTRAINT fk_observation_photo FOREIGN KEY (source_photo_id) REFERENCES photo (id),
    INDEX idx_observation_report (report_id, status, row_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
