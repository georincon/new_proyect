plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    group = "co.org.avance.ssi"
    version = "0.1.0"
    repositories { mavenCentral() }
}
