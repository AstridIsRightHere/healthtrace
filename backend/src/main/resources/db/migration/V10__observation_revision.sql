-- FR-014 增强：正式结果按行修改，保留医疗审计链。
-- 修改不覆盖旧行：旧行标记 SUPERSEDED，新行以 supersedes_id 指向旧行；删除只标记 RETRACTED，不物理删除。
-- 趋势、对比、字典映射等查询只读取 FORMAL 行，因此被替代 / 撤回的行自动退出统计。
ALTER TABLE observation
    MODIFY COLUMN status ENUM ('DRAFT','FORMAL','DELETING','SUPERSEDED','RETRACTED') NOT NULL,
    ADD COLUMN supersedes_id       BIGINT   NULL COMMENT '本行替代的旧版本行（修改产生的新行）',
    ADD COLUMN created_revision_id BIGINT   NULL COMMENT '产生本行的修改记录；确认时生成的行为空',
    ADD COLUMN retired_revision_id BIGINT   NULL COMMENT '使本行失效（被替代 / 撤回）的修改记录',
    ADD COLUMN retired_at          DATETIME NULL COMMENT '失效时间',
    ADD CONSTRAINT fk_observation_supersedes FOREIGN KEY (supersedes_id) REFERENCES observation (id),
    ADD CONSTRAINT fk_observation_created_revision FOREIGN KEY (created_revision_id) REFERENCES report_revision (id),
    ADD CONSTRAINT fk_observation_retired_revision FOREIGN KEY (retired_revision_id) REFERENCES report_revision (id),
    -- 一行最多被替代一次：同一旧行不能出现两个后继版本
    ADD UNIQUE KEY uk_observation_supersedes (supersedes_id),
    -- 失效状态与失效记录必须同时存在
    ADD CONSTRAINT ck_observation_retired CHECK (
        (status IN ('SUPERSEDED', 'RETRACTED') AND retired_revision_id IS NOT NULL AND retired_at IS NOT NULL)
            OR (status NOT IN ('SUPERSEDED', 'RETRACTED') AND retired_revision_id IS NULL AND retired_at IS NULL));
