-- V3：病历报告与原图（FR-004/FR-005，docs/19 REPORT / PHOTO）
-- 所有 DATETIME 均为 UTC，由应用写入；ADR-009：已执行迁移不原地修改。

CREATE TABLE report (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    profile_id           BIGINT       NOT NULL,
    -- 上传账号（审计用，不参与权限判断；权限始终以 profile_access 为准）
    created_by           BIGINT       NOT NULL,
    title                VARCHAR(120) NULL,
    report_type          ENUM ('BLOOD_ROUTINE', 'LIVER', 'KIDNEY', 'OTHER', 'UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
    -- 检查日期：支持历史补录，与上传时间 created_at 分别记录（FR-004）
    examination_date     DATE         NULL,
    processing_status    ENUM ('UPLOADING', 'RECOGNIZING', 'PENDING_CONFIRMATION', 'CONFIRMED', 'FAILED') NOT NULL,
    visibility_status    ENUM ('NORMAL', 'LIST_REMOVED', 'DELETING', 'DELETED') NOT NULL,
    confirmed_by         BIGINT       NULL,
    confirmed_at         DATETIME     NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    source_hospital_text VARCHAR(160) NULL,
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_report_profile FOREIGN KEY (profile_id) REFERENCES patient_profile (id),
    CONSTRAINT fk_report_created_by FOREIGN KEY (created_by) REFERENCES account (id),
    CONSTRAINT fk_report_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES account (id),
    INDEX idx_report_profile_status (profile_id, visibility_status, processing_status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE photo (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    report_id          BIGINT       NOT NULL,
    -- 冗余所属档案：同档重复提示直接走 (profile_id, content_digest) 索引
    profile_id         BIGINT       NOT NULL,
    -- 私有对象键（非公开 URL、非本地绝对路径），存储实现无关
    object_key         VARCHAR(512) NOT NULL,
    -- 原件 SHA-256 小写十六进制，仅用于重复提示，不自动合并（FR-005）
    content_digest     VARCHAR(128) NOT NULL,
    -- 服务端按文件头检测的实际类型
    media_type         VARCHAR(80)  NOT NULL,
    byte_size          BIGINT       NOT NULL,
    -- 客户端压缩预览图（长边 2048px，DEC-008）；HEIC/PDF 无预览
    preview_object_key VARCHAR(512) NULL,
    preview_media_type VARCHAR(80)  NULL,
    preview_byte_size  BIGINT       NULL,
    page_order         INT          NOT NULL,
    storage_status     ENUM ('PENDING', 'COMPLETE', 'FAILED', 'DELETING') NOT NULL,
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NOT NULL,
    -- 只有待上传/完整的图片占用页序；失败记录保留供排查，不阻塞同页重试
    active_page_order  INT GENERATED ALWAYS AS (IF(storage_status IN ('PENDING', 'COMPLETE'), page_order, NULL)) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_photo_report_page UNIQUE (report_id, active_page_order),
    CONSTRAINT uk_photo_object_key UNIQUE (object_key),
    CONSTRAINT fk_photo_report FOREIGN KEY (report_id) REFERENCES report (id),
    CONSTRAINT fk_photo_profile FOREIGN KEY (profile_id) REFERENCES patient_profile (id),
    INDEX idx_photo_profile_digest (profile_id, content_digest)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
