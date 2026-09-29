plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

// ERSo 2026-001/003: lado del titular (custodia de claves, DID, almacén). Sin dependencias de servicios.
dependencies {
    api(project(":did-core"))
    api(project(":credentials-core"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
