# syntax=docker/dockerfile:1
# 前端 + Nginx 镜像：构建 Vite 产物，交给 Nginx 提供静态文件并反向代理 /api 到后端。
# 构建上下文为仓库根目录：docker build -f deploy/frontend.Dockerfile .

FROM node:24-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM nginx:1.28-alpine
# 去掉默认站点，只保留本项目配置
RUN rm -f /etc/nginx/conf.d/default.conf
COPY deploy/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx-proxy.conf /etc/nginx/healthtrace-proxy.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80 443
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
