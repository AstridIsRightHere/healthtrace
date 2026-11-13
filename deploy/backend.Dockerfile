# syntax=docker/dockerfile:1
# 后端镜像（多阶段）：构建阶段用 Maven + JDK 25 打包，运行阶段只保留 JRE 与 jar，以非 root 用户运行。
# 构建上下文为仓库根目录：docker build -f deploy/backend.Dockerfile .

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# 先只拷贝 pom 预取依赖，源码变动时可复用依赖层
COPY backend/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline
COPY backend/src ./src
# 测试在 CI（mvn verify + Testcontainers）中执行，镜像构建不重复跑
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package \
    && cp target/healthtrace-backend-*.jar /src/app.jar

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 healthtrace \
    && useradd --system --uid 10001 --gid healthtrace --home-dir /app --shell /usr/sbin/nologin healthtrace \
    && mkdir -p /app /data/storage /tmp/healthtrace \
    && chown -R healthtrace:healthtrace /app /data/storage /tmp/healthtrace \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build --chown=healthtrace:healthtrace /src/app.jar /app/app.jar
USER healthtrace

# 内存预算：容器上限 512 MiB（compose mem_limit）。堆 320 MiB + 元空间 128 MiB + 线程栈/代码缓存/直接内存约 60 MiB。
# 可在 .env.prod 中用 JAVA_OPTS 覆盖。
ENV JAVA_OPTS="-Xms128m -Xmx320m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=32m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp/healthtrace -Dfile.encoding=UTF-8" \
    SPRING_PROFILES_ACTIVE=prod \
    HT_STORAGE_TYPE=local \
    HT_STORAGE_LOCAL_ROOT=/data/storage

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS -o /dev/null http://127.0.0.1:8080/api/v1/csrf-token || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
