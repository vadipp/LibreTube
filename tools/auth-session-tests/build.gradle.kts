plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("../../app/src/main/java")
        kotlin.include("com/github/libretube/api/AutheliaSession.kt")
    }
}

dependencies {
    implementation(libs.logging.interceptor)
    implementation(libs.kotlinx.serialization)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

tasks.test { testLogging { events("failed", "passed") } }
