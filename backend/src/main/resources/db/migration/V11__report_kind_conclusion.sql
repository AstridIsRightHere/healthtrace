-- V11：支持影像/非结构化报告（docs/32 任务二）
-- 影像报告（超声、X光、CT等）有结论文本，但无结构化指标。
-- 非结构化报告保留后续扩展（如纸质扫描件的未解析内容）。

ALTER TABLE report
    ADD COLUMN report_kind ENUM('STRUCTURED', 'IMAGING', 'UNSTRUCTURED')
        NOT NULL DEFAULT 'STRUCTURED'
        COMMENT '报告类型：STRUCTURED=结构化检验报告（有观测值），IMAGING=影像报告（超声/X光/CT等），UNSTRUCTURED=其他非结构化内容'
        AFTER report_type,
    ADD COLUMN conclusion_text TEXT NULL
        COMMENT '影像报告诊断结论或摘要；结构化报告此字段为空'
        AFTER source_hospital_text,
    ADD COLUMN source_file_extension VARCHAR(16) NULL
        COMMENT '原始文件扩展名（如 .pdf .jpg）；用于区分上传格式'
        AFTER conclusion_text;

-- 为 report_kind 添加索引以支持按类型过滤查询
CREATE INDEX idx_report_profile_kind
    ON report(profile_id, report_kind, visibility_status, processing_status, created_at);
