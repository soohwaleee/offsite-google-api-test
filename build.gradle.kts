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

    // Google Ads Java client (v17+ supports v23 API)
    // https://github.com/googleads/google-ads-java/releases — verify current version on clone
    implementation("com.google.api-ads:google-ads:36.0.0")

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

tasks.withType<Test> {
    useJUnitPlatform()

    // Pass env vars through to tests
    systemProperty("GOOGLE_ADS_CLIENT_ID", System.getenv("GOOGLE_ADS_CLIENT_ID") ?: "")
    systemProperty("GOOGLE_ADS_CLIENT_SECRET", System.getenv("GOOGLE_ADS_CLIENT_SECRET") ?: "")
    systemProperty("GOOGLE_ADS_DEVELOPER_TOKEN", System.getenv("GOOGLE_ADS_DEVELOPER_TOKEN") ?: "")
    systemProperty("GOOGLE_ADS_REFRESH_TOKEN", System.getenv("GOOGLE_ADS_REFRESH_TOKEN") ?: "")
    systemProperty("GOOGLE_ADS_LOGIN_CUSTOMER_ID", System.getenv("GOOGLE_ADS_LOGIN_CUSTOMER_ID") ?: "")
    systemProperty("GOOGLE_ADS_CUSTOMER_ID", System.getenv("GOOGLE_ADS_CUSTOMER_ID") ?: "")

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
