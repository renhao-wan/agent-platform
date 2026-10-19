# 多阶段构建：编译在容器内完成，运行镜像自带 jar，不依赖宿主机预构建
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
# 知识库语料随镜像发布；KNOWLEDGE_DIR 默认值 knowledge 即解析到 /app/knowledge
COPY knowledge ./knowledge
ENTRYPOINT ["java", "-jar", "app.jar"]
