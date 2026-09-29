plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

// ERSo 2026-002: formatos de credencial (dc+sd-jwt, mso_mdoc). No depende de ningún servicio ni de la cartera.
dependencies {
    api(project(":did-core"))
    api(libs.cbor)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test { useJUnitPlatform() }
