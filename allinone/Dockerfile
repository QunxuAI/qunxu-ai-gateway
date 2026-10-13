# ===== QunxuAI-Gateway 一体化镜像（多阶段构建，从源码一键出镜像）=====
# 一个容器内运行全部组件：MySQL + Redis + 后端(Spring Boot) + 前端(Nginx)
# 用法（在仓库根目录执行）：
#   docker build -t qunxu-ai-gateway:0.1.0 -f allinone/Dockerfile .
#   docker run -d -p 80:80 -p 48080:48080 --name qunxu-ai-gateway qunxu-ai-gateway:0.1.0
#   然后访问 http://localhost 即可使用完整管理后台（默认账号 admin / admin123）

# ===== 阶段 1：Maven 构建后端 jar =====
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /workspace

# 先拷贝 pom 与依赖定义，最大化利用 Docker 层缓存
COPY pom.xml pom.xml
COPY qunxu-dependencies/pom.xml qunxu-dependencies/pom.xml
COPY qunxu-framework/pom.xml qunxu-framework/pom.xml
COPY qunxu-framework/qunxu-common/pom.xml qunxu-framework/qunxu-common/pom.xml
COPY qunxu-framework/qunxu-spring-boot-starter-redis/pom.xml qunxu-framework/qunxu-spring-boot-starter-redis/pom.xml
COPY qunxu-framework/qunxu-spring-boot-starter-web/pom.xml qunxu-framework/qunxu-spring-boot-starter-web/pom.xml
COPY qunxu-framework/qunxu-spring-boot-starter-security/pom.xml qunxu-framework/qunxu-spring-boot-starter-security/pom.xml
COPY qunxu-framework/qunxu-spring-boot-starter-mybatis/pom.xml qunxu-framework/qunxu-spring-boot-starter-mybatis/pom.xml
COPY qunxu-framework/qunxu-spring-boot-starter-excel/pom.xml qunxu-framework/qunxu-spring-boot-starter-excel/pom.xml
COPY qunxu-framework/qunxu-spring-boot-starter-test/pom.xml qunxu-framework/qunxu-spring-boot-starter-test/pom.xml
COPY qunxu-framework/qunxu-spring-boot-starter-biz-tenant/pom.xml qunxu-framework/qunxu-spring-boot-starter-biz-tenant/pom.xml
COPY qunxu-framework/qunxu-spring-boot-starter-biz-data-permission/pom.xml qunxu-framework/qunxu-spring-boot-starter-biz-data-permission/pom.xml
COPY qunxu-module-system/pom.xml qunxu-module-system/pom.xml
COPY qunxu-module-gateway/pom.xml qunxu-module-gateway/pom.xml
COPY qunxu-server/pom.xml qunxu-server/pom.xml

# 预下载依赖（-DskipTests 避免触发测试，仅解析依赖）
RUN mvn -B -pl qunxu-server -am dependency:go-offline -DskipTests || true

# 拷贝全部源码与 SQL 资源
COPY qunxu-framework qunxu-framework
COPY qunxu-module-system qunxu-module-system
COPY qunxu-module-gateway qunxu-module-gateway
COPY qunxu-server qunxu-server

# 打包（跳过测试，生产构建以速度优先）
RUN mvn -B -pl qunxu-server -am package -DskipTests

# ===== 阶段 2：Node 构建前端 dist =====
FROM node:22-alpine AS frontend-build
WORKDIR /app
COPY qunxu-ui/package.json qunxu-ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY qunxu-ui/ ./
RUN npm run build

# ===== 阶段 3：运行时（MySQL + Redis + 后端 + Nginx）=====
FROM eclipse-temurin:21-jre

LABEL maintainer="qunxu-ai"
LABEL description="QunxuAI-Gateway all-in-one — MySQL + Redis + backend + frontend"

ENV TZ=Asia/Shanghai

# ---- 1. 安装 MySQL / Redis / Nginx / Supervisor ----
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       mysql-server redis-server nginx supervisor \
    && rm -rf /var/lib/apt/lists/*

# ---- 2. 拷贝应用产物 ----
COPY --from=backend-build /workspace/qunxu-server/target/qunxu-server.jar /app/qunxu-server.jar
COPY --from=frontend-build /app/dist /usr/share/nginx/html
# SQL 初始化脚本
COPY sql/schema.sql /docker-entrypoint-initdb.d/01-schema.sql
COPY sql/data.sql /docker-entrypoint-initdb.d/02-data.sql

# ---- 3. 配置 ----
# Nginx：托管前端 + 反代 /admin-api 到本机后端（先移除 Ubuntu 默认站点，避免 default_server 抢占 80）
RUN rm -f /etc/nginx/sites-enabled/default
COPY allinone/nginx.conf /etc/nginx/conf.d/default.conf
# Supervisor：管理 4 个进程
COPY allinone/supervisord.conf /etc/supervisor/conf.d/qunxu.conf
# 启动入口：初始化数据库（仅首次）后交给 supervisor
COPY allinone/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# ---- 4. 数据目录（可挂载卷持久化）----
VOLUME ["/var/lib/mysql", "/var/lib/redis", "/app/logs"]

# ---- 5. 端口 ----
# 80: 前端 Web（含 /admin-api 反代）  48080: 后端 API 直连
EXPOSE 80 48080

HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/80 && exec 4<>/dev/tcp/127.0.0.1/48080' || exit 1

ENTRYPOINT ["/entrypoint.sh"]
