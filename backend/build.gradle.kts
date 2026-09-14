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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // 分散式鎖。用來讓沒有互斥保護的 @Scheduled 排程在多副本下只執行一次 —— 2026-09-13 在
    // 三副本 k3s 上實測到重複執行會造成庫存超賣，見
    // docs/portfolio/scheduler-duplication-evidence.md。
    // starter 讀既有的 spring.data.redis.* 設定，不需要額外的 Redisson 設定檔。
    implementation("org.redisson:redisson-spring-boot-starter:3.35.0")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("io.zipkin.reporter2:zipkin-reporter-brave")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers:1.20.1")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testImplementation("org.testcontainers:postgresql:1.20.1")
    testImplementation("org.testcontainers:rabbitmq:1.20.1")
    testImplementation("org.awaitility:awaitility:4.2.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("io.micrometer:micrometer-observation-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "2g"
    systemProperty("spring.test.context.cache.maxSize", "10")
    testLogging {
        events("passed", "skipped", "failed")
        // CI 上看不到測試報告檔，只有這段 console 輸出。預設格式只印例外鏈、不印訊息本文，
        // 而「RabbitMQ 為什麼關掉 channel」這種問題的答案就在被截掉的那一行裡。
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
