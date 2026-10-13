# ===== 多阶段构建：Maven 编译打包 + JRE 运行 =====
# 构建阶段：使用带 JDK 21 + Maven 的镜像
FROM maven:3.9-eclipse-temurin-21 AS build

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
COPY sql sql

# 打包（跳过测试，生产构建以速度优先）
RUN mvn -B -pl qunxu-server -am package -DskipTests

# ===== 运行阶段：仅 JRE，镜像最小化 =====
FROM eclipse-temurin:21-jre

LABEL maintainer="qunxu-ai"
LABEL description="QunxuAI-Gateway — 统一 AI 模型网关"

# 时区
ENV TZ=Asia/Shanghai

# 非 root 用户运行（安全加固）
RUN useradd -r -u 1001 appuser

# 复制构建产物
COPY --from=build /workspace/qunxu-server/target/qunxu-server.jar /app/qunxu-server.jar

WORKDIR /app

# 预创建日志目录并授权给非 root 用户（应用以 appuser 运行，需可写 logs/）
RUN mkdir -p /app/logs && chown -R appuser:appuser /app

USER appuser

# 对外暴露端口
EXPOSE 48080

# 启动命令：默认启用 docker profile（连接信息来自环境变量）
ENTRYPOINT ["java", "-jar", "/app/qunxu-server.jar", "--spring.profiles.active=docker"]
