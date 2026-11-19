-- V14：管理员角色（FR-018）
-- 为 account 表增加 role 列，默认 USER；已有账号保持 USER

ALTER TABLE account
    ADD COLUMN role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER' AFTER status;
