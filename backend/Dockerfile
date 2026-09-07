FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# go-offline 仅用于预热依赖缓存，偶发 Maven Central 502/超时；
# 失败不终止构建，缺失依赖由下一步 package 阶段补齐
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# 2G 服务器内存上限，不要随手调大
ENV JAVA_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m" \
    TZ=Asia/Shanghai
COPY --from=build /app/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
