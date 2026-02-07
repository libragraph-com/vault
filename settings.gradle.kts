pluginManagement {
    val quarkusVersion: String by settings
    val protobufPluginVersion: String by settings
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.quarkus") version quarkusVersion
        id("com.google.protobuf") version protobufPluginVersion
    }
}

rootProject.name = "vault"

include(
    "shared:types",
    "shared:utils",
    "modules:core",
    "modules:formats",
    "modules:api",
    "app",
)
