#!/usr/bin/env bash
# HealthTrace 生产部署脚本：检查配置 → 构建镜像 → 启动/滚动更新 → 等待健康检查。
# 用法（在部署机仓库根目录或 deploy/ 下均可）：
#   ./deploy/deploy.sh            构建并启动全部服务
#   ./deploy/deploy.sh --no-build 仅用已有镜像重启（回滚时配合 HT_IMAGE_TAG）
#   ./deploy/deploy.sh status     查看服务状态
#   ./deploy/deploy.sh logs       跟踪后端日志
# 只打印变量名，绝不回显任何密钥值。
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${DEPLOY_DIR}/.env.prod"
COMPOSE=(docker compose -f "${DEPLOY_DIR}/docker-compose.prod.yml" --env-file "${ENV_FILE}")

log() { printf '[deploy] %s\n' "$*"; }
die() { printf '[deploy] 错误：%s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "未安装 docker"
docker compose version >/dev/null 2>&1 || die "需要 Docker Compose v2（docker compose）"
[[ -f "${ENV_FILE}" ]] || die "缺少 ${ENV_FILE}：请复制 deploy/.env.prod.example 并填写"

case "${1:-}" in
  status) "${COMPOSE[@]}" ps; exit 0 ;;
  logs) "${COMPOSE[@]}" logs -f --tail=200 backend; exit 0 ;;
esac

# .env.prod 只允许部署用户读写
perm=$(stat -c '%a' "${ENV_FILE}" 2>/dev/null || echo "")
if [[ -n "${perm}" && "${perm}" != "600" && "${perm}" != "400" ]]; then
  log "警告：${ENV_FILE} 权限为 ${perm}，建议 chmod 600"
fi

# 读取变量值只用于校验，不输出
env_value() {
  local name=$1 line
  line=$(grep -E "^${name}=" "${ENV_FILE}" | tail -n 1 || true)
  printf '%s' "${line#*=}"
}

required=(MYSQL_ROOT_PASSWORD MYSQL_DATABASE MYSQL_USER MYSQL_PASSWORD
  HT_EMAIL_ENCRYPTION_KEY HT_EMAIL_INDEX_KEY HT_STORAGE_ENCRYPTION_KEY
  HT_OCR_ENCRYPTION_KEY HT_BAIDU_OCR_API_KEY HT_BAIDU_OCR_SECRET_KEY)
missing=()
for v in "${required[@]}"; do
  [[ -n "$(env_value "$v")" ]] || missing+=("$v")
done
(( ${#missing[@]} == 0 )) || die "以下变量缺失或为空：${missing[*]}"

# 四把应用密钥必须是 32 字节 Base64，且互不相同
keys=(HT_EMAIL_ENCRYPTION_KEY HT_EMAIL_INDEX_KEY HT_STORAGE_ENCRYPTION_KEY HT_OCR_ENCRYPTION_KEY)
declare -A seen=()
for k in "${keys[@]}"; do
  val=$(env_value "$k")
  len=$(printf '%s' "$val" | base64 -d 2>/dev/null | wc -c || echo 0)
  [[ "$len" -eq 32 ]] || die "$k 不是 32 字节的 Base64（用 openssl rand -base64 32 生成）"
  digest=$(printf '%s' "$val" | sha256sum | cut -c1-16)
  [[ -z "${seen[$digest]:-}" ]] || die "$k 与 ${seen[$digest]} 相同，每把密钥必须独立生成"
  seen[$digest]=$k
done
unset val seen

cert_dir=$(env_value HT_CERT_DIR); cert_dir=${cert_dir:-./certs}
[[ "${cert_dir}" = /* ]] || cert_dir="${DEPLOY_DIR}/${cert_dir#./}"
[[ -f "${cert_dir}/fullchain.pem" && -f "${cert_dir}/privkey.pem" ]] \
  || die "证书缺失：需要 ${cert_dir}/fullchain.pem 与 privkey.pem（见 deploy/README.md）"

if [[ -z "$(env_value HT_IMAGE_TAG)" ]]; then
  HT_IMAGE_TAG=$(git -C "${DEPLOY_DIR}/.." rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M)
  export HT_IMAGE_TAG
fi
log "镜像标签：${HT_IMAGE_TAG:-$(env_value HT_IMAGE_TAG)}"

"${COMPOSE[@]}" config --quiet || die "compose 配置校验失败"

if [[ "${1:-}" != "--no-build" ]]; then
  log "构建镜像…"
  DOCKER_BUILDKIT=1 "${COMPOSE[@]}" build --pull
fi

log "启动服务…"
"${COMPOSE[@]}" up -d --remove-orphans

log "等待健康检查（最长 5 分钟）…"
deadline=$((SECONDS + 300))
for svc in mysql backend nginx; do
  while :; do
    cid=$("${COMPOSE[@]}" ps -q "$svc")
    state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo unknown)
    [[ "$state" == "healthy" ]] && { log "$svc: healthy"; break; }
    if (( SECONDS > deadline )); then
      "${COMPOSE[@]}" logs --tail=80 "$svc" >&2 || true
      die "$svc 未在规定时间内就绪（状态 $state）"
    fi
    sleep 5
  done
done

docker image prune -f >/dev/null 2>&1 || true
log "部署完成。可执行 ./deploy/deploy.sh status 查看状态。"
