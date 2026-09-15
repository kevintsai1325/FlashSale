FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew
RUN ./gradlew --no-daemon dependencies || true
COPY src src
RUN ./gradlew --no-daemon shadowJar

# Application mode：job 的 jar 烤進映像，JobManager 啟動時直接跑它。
# 相對於 session cluster 少一個「提交 job」的步驟，也少一個會失敗的環節 ——
# 而那個步驟在宣告式部署裡本來就很難做對（誰負責提交？重啟之後誰再提交一次？）。
FROM flink:1.20-java17
COPY --from=build /app/build/libs/flink-jobs-0.1.0.jar /opt/flink/usrlib/flink-jobs.jar
