pluginManagement {
    val quarkusVersion: String by settings
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.quarkus") version quarkusVersion
    }
}

rootProject.name = "vault"

include(
    "shared:types",
    "modules:core",
    "modules:api",
    "app",
)
