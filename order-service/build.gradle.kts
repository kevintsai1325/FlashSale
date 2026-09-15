plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.flashsale"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// 獨立的 Gradle 專案，不依賴 backend 也不依賴 purchase-service。
// 三個服務之間共用的是契約（事件的 JSON 形狀、內部 HTTP API），不是型別 —— 理由同 purchase-service。
// 因此 outbox 機制、佇列常數、錯誤回應格式、JWT 驗證設定各有一份複本。
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // 兩個排程（付款逾時、庫存對帳）在多副本下必須互斥，理由與 backend 相同。
    implementation("org.redisson:redisson-spring-boot-starter:3.35.0")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("io.zipkin.reporter2:zipkin-reporter-brave")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers:1.20.1")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testImplementation("org.testcontainers:postgresql:1.20.1")
    testImplementation("org.testcontainers:rabbitmq:1.20.1")
    testImplementation("org.testcontainers:kafka:1.20.1")
    testImplementation("org.awaitility:awaitility:4.2.2")
    testImplementation("io.micrometer:micrometer-observation-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
