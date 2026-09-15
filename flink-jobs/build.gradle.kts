plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.flashsale"
version = "0.1.0"

// Flink 1.20 官方映像跑的是 Java 17，所以 class 檔的版本必須是 17。
// 用 options.release 而不是 toolchain：release 讓任何 >= 17 的 JDK 都能產出正確的
// bytecode 版本並對著 17 的 API 編譯，不必在每台機器上多裝一個 JDK。
// 版本不對的症狀是 TaskManager 上的 UnsupportedClassVersionError —— 要等 job 真的被
// 排程才會出現，本機建置完全看不到。
tasks.withType<JavaCompile> {
    options.release.set(17)
}

repositories {
    mavenCentral()
}

val flinkVersion = "1.20.0"

dependencies {
    // compileOnly：這些類別由 Flink 的執行環境提供。打進 jar 裡會與叢集上的版本相撞，
    // 症狀是各種 NoSuchMethodError。
    compileOnly("org.apache.flink:flink-streaming-java:$flinkVersion")
    compileOnly("org.apache.flink:flink-clients:$flinkVersion")
    // DeliveryGuarantee 住在 flink-connector-base，而它在 Flink 的 distribution 裡 ——
    // 所以是 compileOnly，不能打進 jar。
    compileOnly("org.apache.flink:flink-connector-base:$flinkVersion")
    // connector 與 JSON 不在 Flink 的 distribution 裡，必須打進 jar。
    implementation("org.apache.flink:flink-connector-kafka:3.3.0-1.20")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.apache.flink:flink-streaming-java:$flinkVersion")
    testImplementation("org.apache.flink:flink-clients:$flinkVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Flink 的 job jar 必須自帶 connector，但不能自帶 Flink 本身。
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
