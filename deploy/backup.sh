#!/usr/bin/env bash
# HealthTrace 备份：MySQL 逻辑备份 + 照片存储卷打包，按日期存放并清理过期备份（DEC-003 待确认，默认保留 14 天）。
# 用法：./deploy/backup.sh [备份目录]，建议加入 crontab：  30 3 * * * /opt/healthtrace/deploy/backup.sh
# 说明：
# - 照片文件本身已是 AES-256-GCM 密文；数据库备份含已加密的邮箱/OCR 原文，但其余字段是明文，备份文件按敏感数据管理。
# - 解密所需密钥（.env.prod）不在备份内，必须另行离线保存；缺了 HT_STORAGE_ENCRYPTION_KEY 照片无法恢复。
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${DEPLOY_DIR}/.env.prod"
COMPOSE=(docker compose -f "${DEPLOY_DIR}/docker-compose.prod.yml" --env-file "${ENV_FILE}")
BACKUP_ROOT="${1:-${DEPLOY_DIR}/backups}"
RETENTION_DAYS="${HT_BACKUP_RETENTION_DAYS:-14}"
STAMP=$(date +%Y%m%d-%H%M%S)
TARGET="${BACKUP_ROOT}/${STAMP}"

umask 077
mkdir -p "${TARGET}"

echo "[backup] 数据库 → ${TARGET}/mysql.sql.gz"
# 密码在容器内从环境变量读取，不出现在宿主机命令行
"${COMPOSE[@]}" exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --hex-blob --default-character-set=utf8mb4 --set-gtid-purged=OFF "$MYSQL_DATABASE"' \
  | gzip -9 > "${TARGET}/mysql.sql.gz"

echo "[backup] 照片存储 → ${TARGET}/storage.tar.gz"
"${COMPOSE[@]}" exec -T backend sh -c 'cd /data/storage && tar -cf - .' | gzip -1 > "${TARGET}/storage.tar.gz"

( cd "${TARGET}" && sha256sum mysql.sql.gz storage.tar.gz > SHA256SUMS )
echo "[backup] 完成：$(du -sh "${TARGET}" | cut -f1)"

find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" -print -exec rm -rf {} +
