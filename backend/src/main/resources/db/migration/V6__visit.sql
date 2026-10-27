-- V6：就诊时间线（FR-013，docs/19 VISIT / VISIT_REPORT，docs/06 第 6 条）
-- 就诊日 visit_date 与报告检查日 examination_date 分别记录；就诊次数按 visit 行计数，关联报告不增加次数。
-- 本迭代医院只做手填（hospital_text），HOSPITAL 标准目录尚未建表，hospital_id 预留为可空列、暂不加外键。
-- 所有 DATETIME 均为 UTC，由应用写入；ADR-009：已执行迁移不原地修改。

CREATE TABLE visit
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    profile_id    BIGINT       NOT NULL,
    -- 创建账号（审计用，不参与权限判断；权限始终以 profile_access 为准）
    created_by    BIGINT       NOT NULL,
    visit_date    DATE         NOT NULL,
    hospital_id   BIGINT       NULL,
    hospital_text VARCHAR(160) NOT NULL,
    department    VARCHAR(80)  NULL,
    note          TEXT         NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_visit_profile FOREIGN KEY (profile_id) REFERENCES patient_profile (id),
    CONSTRAINT fk_visit_created_by FOREIGN KEY (created_by) REFERENCES account (id),
    INDEX idx_visit_profile_date (profile_id, visit_date, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 多对多：一份报告可关联多次就诊，一次就诊可关联多份报告；必须同档案（由应用校验）。
-- 删除就诊只删除关联行，不删除报告本身；未确认报告被物理删除时其关联行随之删除，不影响就诊记录。
CREATE TABLE visit_report
(
    visit_id   BIGINT   NOT NULL,
    report_id  BIGINT   NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (visit_id, report_id),
    CONSTRAINT fk_visit_report_visit FOREIGN KEY (visit_id) REFERENCES visit (id) ON DELETE CASCADE,
    CONSTRAINT fk_visit_report_report FOREIGN KEY (report_id) REFERENCES report (id) ON DELETE CASCADE,
    INDEX idx_visit_report_report (report_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
