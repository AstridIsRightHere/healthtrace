# HealthTrace 生产部署说明

单机 Docker Compose 部署：**Nginx**（HTTPS、静态文件、反向代理）+ **后端**（Spring Boot，JVM ≤ 512 MiB）+ **MySQL 8.4**。只有 Nginx 暴露 80/443 端口，后端和数据库只在内部网络 `backend-net` 中可达。

> 当前状态：部署文件已经过 `docker compose config`、镜像构建和 `nginx -t` 校验，还没在真实服务器上演练过。正式使用真实健康资料前，仍需完成 [docs/10](../docs/10-风险清单与合规注意事项.md) 列出的合规事项（隐私政策定稿、供应商合同、备份与删除规则 DEC-002/003 等）。

## 目录

| 文件 | 作用 |
| --- | --- |
| `docker-compose.prod.yml` | 三个服务、数据卷、内存上限、健康检查 |
| `backend.Dockerfile` | 多阶段构建：Maven + JDK 25 打包 → JRE 25 运行，非 root（uid 10001），只读根文件系统 |
| `frontend.Dockerfile` | Node 24 构建前端 → Nginx 1.28 提供静态文件 |
| `nginx.conf` / `nginx-proxy.conf` | HTTPS、安全响应头（CSP/HSTS 等）、`/api` 反向代理、登录限流、SW 与入口页不缓存 |
| `.env.prod.example` | 生产环境变量清单（只有变量名） |
| `mail.env.example` | 可选的 SMTP 发信配置 |
| `deploy.sh` | 校验配置 → 构建 → 启动 → 等待健康 |
| `backup.sh` | 数据库 + 照片存储备份，默认保留 14 天 |

## 首次部署

前提：Linux 主机（2 核 / 2 GiB 内存起），已装 Docker Engine 24+ 与 Compose v2，域名已解析到主机。

```bash
git clone https://github.com/AstridIsRightHere/healthtrace.git /opt/healthtrace
cd /opt/healthtrace

# 1. 环境变量：每把密钥单独生成，互不相同
cp deploy/.env.prod.example deploy/.env.prod
chmod 600 deploy/.env.prod
openssl rand -base64 32   # 依次填入 HT_EMAIL_ENCRYPTION_KEY、HT_EMAIL_INDEX_KEY、HT_STORAGE_ENCRYPTION_KEY、HT_OCR_ENCRYPTION_KEY
openssl rand -base64 24   # MYSQL_ROOT_PASSWORD、MYSQL_PASSWORD
vi deploy/.env.prod       # 再填百度 OCR 凭据

# 2. 证书：放到 deploy/certs/（已被 .gitignore 忽略）
mkdir -p deploy/certs
cp /path/to/fullchain.pem /path/to/privkey.pem deploy/certs/
chmod 600 deploy/certs/privkey.pem

# 3. 可选：启用邮件
cp deploy/mail.env.example deploy/mail.env && chmod 600 deploy/mail.env && vi deploy/mail.env

# 4. 部署
./deploy/deploy.sh
```

`deploy.sh` 只输出变量名，不回显任何密钥值。它会检查：必填变量非空；四把应用密钥都是 32 字节 Base64 且互不相同；证书文件存在；compose 配置有效。启动后依次等待 mysql → backend → nginx 健康。Flyway 会在后端启动时自动执行数据库迁移。

## 密钥管理（务必阅读）

- **`HT_STORAGE_ENCRYPTION_KEY`**：照片原件在写盘前用 AES-256-GCM 加密（`FileCipher`）。密钥丢失或更换后，已有照片**永久无法解密**。目前不支持密钥轮换。
- 邮箱密钥、OCR 原文密钥同理：丢失后对应数据无法读取。
- 把 `deploy/.env.prod` 离线保存一份（密码管理器或加密 U 盘），**不要**和数据库备份放在一起。
- 开发、测试、生产的密钥各自独立生成，不共享。

## 日常运维

```bash
./deploy/deploy.sh status          # 服务状态
./deploy/deploy.sh logs            # 跟踪后端日志
git pull && ./deploy/deploy.sh     # 升级：镜像标签默认是 git 短哈希
HT_IMAGE_TAG=<旧哈希> ./deploy/deploy.sh --no-build   # 回滚到旧镜像（数据库迁移不可回退，回滚前先备份）
```

## 备份与恢复

```bash
./deploy/backup.sh                 # 输出到 deploy/backups/<时间戳>/（已被 .gitignore 忽略）
# crontab -e 示例：每天 03:30
30 3 * * * /opt/healthtrace/deploy/backup.sh >> /var/log/healthtrace-backup.log 2>&1
```

备份目录包含 `mysql.sql.gz`、`storage.tar.gz` 和 `SHA256SUMS`。备份应再同步到另一台机器或对象存储；RPO 24 小时 / RTO 8 小时的目标见 NFR-007（DEC-003 待确认）。

恢复（在空环境上）：

```bash
cd deploy/backups/<时间戳> && sha256sum -c SHA256SUMS && cd -
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod up -d mysql
gunzip -c deploy/backups/<时间戳>/mysql.sql.gz | docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod \
  exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod up -d backend
gunzip -c deploy/backups/<时间戳>/storage.tar.gz | docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod \
  exec -T backend sh -c 'cd /data/storage && tar -xf -'
./deploy/deploy.sh --no-build
```

恢复必须使用与备份时相同的 `.env.prod` 密钥。

## 内存预算

| 服务 | 上限 | 说明 |
| --- | --- | --- |
| backend | 512 MiB | 堆 `-Xmx320m`、元空间 128 MiB、代码缓存 48 MiB、直接内存 32 MiB、SerialGC；OOM 时进程退出并由 Docker 重启 |
| mysql | 768 MiB | `innodb_buffer_pool_size=256M`、`max_connections=60` |
| nginx | 128 MiB | |

可以用 `.env.prod` 中的 `JAVA_OPTS` 覆盖 JVM 参数，但堆不能超过约 340 MiB，否则容器会被 OOM Kill。

## 安全要点

- 只有 Nginx 对外，TLS 1.2/1.3，HTTP 一律 301 到 HTTPS；HSTS 有效期 1 年。
- CSP 只允许同源资源（照片/PDF 预览用 `blob:`），`frame-ancestors 'none'`。
- `/api` 响应统一 `Cache-Control: no-store`；`sw.js` 与入口页 `no-cache`，保证 PWA 能更新、且不缓存医疗数据。
- 登录、注册、找回密码接口在 Nginx 层按 IP 限流（每分钟 10 次，突发 5 次），后端仍有自己的失败锁定。
- 访问日志不记录查询串，邮件链接中的 token 不会写进日志。
- Swagger、`/v3/api-docs`、`/actuator` 在 Nginx 层返回 404；prod 下 springdoc 本身也是关闭的。
- 后端容器以非 root 运行，根文件系统只读，`cap_drop: ALL`、`no-new-privileges`。
- 后端信任 Nginx 传入的 `X-Forwarded-*`（`SERVER_FORWARD_HEADERS_STRATEGY=native`），所以后端端口绝不能直接暴露到公网。
