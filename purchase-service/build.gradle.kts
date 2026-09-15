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

// 這是一個獨立的 Gradle 專案，不是 backend 的子模組，也不依賴任何共用 library。
// 兩個服務之間共用的是契約（事件的 JSON 形狀、內部 HTTP API），不是型別——
// 抽一個 shared module 只會把耦合換個地方藏，並且把兩個服務綁在同一個建置與版本上。
// 代價是複製了一些程式碼（outbox 機制、queue 名稱常數、錯誤回應格式、JWT 驗證設定），
// 這是刻意付的代價，不是疏漏。
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("io.zipkin.reporter2:zipkin-reporter-brave")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // P4 步驟 2：自己的資料庫、自己的 migration。步驟 1 時這裡刻意留空，
    // 因為兩個服務對同一個 schema 各跑一套 migration 只會互相打架。
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:rabbitmq")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
