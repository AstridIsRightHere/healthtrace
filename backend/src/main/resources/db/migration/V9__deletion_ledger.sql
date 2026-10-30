-- V9：删除账本与存储清理补偿（FR-016 彻底删除 / FR-017 账号注销；docs/06 删除账本、docs/18 删除事件重放）
-- 账本只保存不可逆标识与状态，不保存明文 id、邮箱、病历内容；无外键：被删对象消失后账本仍须保留。
--   deletion_ledger.event_type REPORT_PURGE(报告彻底删除) / ACCOUNT_CLOSURE(账号注销)
--   deletion_ledger.status     COMPLETED(数据库与存储均已清理) / STORAGE_PENDING(数据库已清理，存储对象待补偿删除)
-- subject_digest = HMAC-SHA256("report:42" / "account:7")，密钥由服务端派生，小写十六进制。
-- 所有 DATETIME 均为 UTC，由应用写入；ADR-009：已执行迁移不原地修改。

CREATE TABLE deletion_ledger
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    event_type     VARCHAR(32) NOT NULL,
    subject_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- 清理的记录/对象数，可空
    item_count     INT         NULL,
    status         VARCHAR(32) NOT NULL,
    requested_at   DATETIME    NOT NULL,
    completed_at   DATETIME    NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_deletion_ledger_event CHECK (event_type IN ('REPORT_PURGE', 'ACCOUNT_CLOSURE')),
    CONSTRAINT ck_deletion_ledger_status CHECK (status IN ('COMPLETED', 'STORAGE_PENDING')),
    INDEX idx_deletion_ledger_subject (subject_digest),
    INDEX idx_deletion_ledger_status (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 存储清理 outbox：与业务删除同事务写入，提交后异步删除对象，成功即删行；失败保留由定时任务重试。
-- ledger_id 关联发起的账本行（可空），对象全部清理后账本转为 COMPLETED。
CREATE TABLE storage_purge_task
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    -- 与 photo.object_key 同长
    object_key      VARCHAR(512) NOT NULL,
    ledger_id       BIGINT       NULL,
    created_at      DATETIME     NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    last_attempt_at DATETIME     NULL,
    PRIMARY KEY (id),
    INDEX idx_storage_purge_task_due (last_attempt_at, id),
    INDEX idx_storage_purge_task_ledger (ledger_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
