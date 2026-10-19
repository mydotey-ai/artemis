# registry-server 单服务镜像（v0.1；management/all-in-one 随 v0.4/v0.3 独立编排）。
# 多阶段自包含：docker build . 一条命令完成，不依赖宿主预构建。

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY java ./java
COPY proto ./proto
RUN mvn -B -f java/pom.xml -pl :artemis-registry-server -am package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home artemis
COPY --from=build /src/java/server/artemis-registry-server/target/artemis-registry-server-*-exec.jar /app/app.jar
# 快照默认路径 data/registry-snapshot.bin 相对工作目录（FileRegistrySnapshotStore）
RUN mkdir -p /app/data && chown -R artemis:artemis /app
USER artemis
# 外部 gRPC / 外部 HTTP；actuator 9091 默认绑 127.0.0.1（application.properties），不对外暴露
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
