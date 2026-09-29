plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("co.org.avance.ssi.vdr.MainKt") }

dependencies {
    implementation(project(":did-core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(project(":did-resolver"))
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
    // Las pruebas de integración usan PostgreSQL real: TEST_DB_URL=jdbc:postgresql://localhost:5432/vdr
    environment("TEST_DB_URL", System.getenv("TEST_DB_URL") ?: "")
}
