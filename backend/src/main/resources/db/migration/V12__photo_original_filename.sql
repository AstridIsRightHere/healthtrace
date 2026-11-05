-- 报告列表封面展示：保存上传时客户端提供的原始文件名（已去除路径与控制字符，最多 255 个字符）。
-- 仅用于展示；文件类型仍以服务端文件头检测的 media_type 为准。历史数据为 NULL。
ALTER TABLE photo
    ADD COLUMN original_filename VARCHAR(255) NULL COMMENT '上传时的原始文件名（已清洗，仅展示用）' AFTER byte_size;
