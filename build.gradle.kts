plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.musinsa"
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
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Google Ads Java client
    //   - v42.0.0+ supports Google Ads API v23
    //   - v42.2.0 supports v23_2 (2026-03-25 release)
    //   https://github.com/googleads/google-ads-java/releases
    implementation("com.google.api-ads:google-ads:42.2.0")

    // For direct REST calls / OAuth refresh when we want to bypass the client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testImplementation("org.assertj:assertj-core")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

val gadsEnv: Map<String, String> = listOf(
    "GOOGLE_ADS_CLIENT_ID",
    "GOOGLE_ADS_CLIENT_SECRET",
    "GOOGLE_ADS_DEVELOPER_TOKEN",
    "GOOGLE_ADS_REFRESH_TOKEN",
    "GOOGLE_ADS_LOGIN_CUSTOMER_ID",
    "GOOGLE_ADS_CUSTOMER_ID",
).associateWith { System.getenv(it) ?: "" }

tasks.withType<Test> {
    useJUnitPlatform()

    // Pass env vars through to tests
    gadsEnv.forEach { (k, v) -> systemProperty(k, v) }

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// ---------- Cleanup tasks ----------
// Google Ads 테스트 리소스를 명시적으로 삭제한다.
// 사용법:
//   ./gradlew cleanupRun        -PrunId=2026-04-22-1530-12345
//   ./gradlew cleanupOrphaned   -PolderThanHours=24
//   ./gradlew cleanupAll        -Pconfirm=true

val cleanupMainClass = "com.musinsa.gads.cleanup.GadsCleanupRunnerKt"

fun JavaExec.configureCleanup(mode: String) {
    group = "cleanup"
    mainClass.set(cleanupMainClass)
    classpath = sourceSets["main"].runtimeClasspath
    gadsEnv.forEach { (k, v) -> systemProperty(k, v) }
    systemProperty("cleanup.mode", mode)
    // Project properties → system properties
    (project.findProperty("runId") as String?)?.let { systemProperty("cleanup.runId", it) }
    (project.findProperty("olderThanHours") as String?)?.let { systemProperty("cleanup.olderThanHours", it) }
    (project.findProperty("confirm") as String?)?.let { systemProperty("cleanup.confirm", it) }
}

tasks.register<JavaExec>("cleanupRun") {
    description = "Delete resources from a specific test run. Required: -PrunId=<id>"
    configureCleanup("run")
}

tasks.register<JavaExec>("cleanupOrphaned") {
    description = "Delete resources from runs older than N hours. Optional: -PolderThanHours=24 (default 24)"
    configureCleanup("orphaned")
}

tasks.register<JavaExec>("cleanupAll") {
    description = "⚠️  Delete ALL tracked resources. Requires: -Pconfirm=true"
    configureCleanup("all")
}
