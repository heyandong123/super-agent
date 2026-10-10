# ==========================================
# 第一阶段：编译打包阶段 (Builder)
# 使用带有 Maven 的 Java 21 镜像来编译代码
# ==========================================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

# 设置工作目录
WORKDIR /build

# 1. 先拷贝 pom.xml 并下载依赖包
# 这样做的好处是利用 Docker 的层缓存，只要 pom.xml 没变，下次打包就不需要重新下载依赖
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 2. 拷贝全部源代码并进行打包，跳过单元测试
COPY src ./src
RUN mvn clean package -DskipTests

# ==========================================
# 第二阶段：运行环境阶段 (Runner)
# 使用极简的 JRE 镜像运行，抛弃臃肿的 Maven 源码
# ==========================================
FROM eclipse-temurin:21-jre-alpine

# 设置时区为上海，防止定时任务和日志时间错乱
ENV TZ=Asia/Shanghai

# 【核心定制】：安装基础依赖、系统字体库（为 PDF 服务）以及 Node.js 环境（为 MCP 服务）
RUN apk add --no-cache \
    tzdata \
    fontconfig \
    ttf-dejavu \
    nodejs \
    npm

# 设置工作目录
WORKDIR /app

# 从第一阶段(builder)的 target 目录中，把打包好的 jar 包拿过来
COPY --from=builder /build/target/yu-ai-agent-0.0.1-SNAPSHOT.jar app.jar

# 暴露你指定的 8123 端口
EXPOSE 8123

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]