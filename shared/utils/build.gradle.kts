// Pure Java utilities — no CDI, no Quarkus.
// Houses ContentHash and the buffer layer (BinaryData, Buffer, RamBuffer).

plugins {
    `java-library`
    `maven-publish`
}

val commonsCodecVersion: String by project

dependencies {
    api("commons-codec:commons-codec:$commonsCodecVersion") // BLAKE3 (exposed via Buffer/BinaryData → ContentHash)
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/libragraph-com/vault")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String? ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String? ?: ""
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "shared-utils"
            // Resolve BOM-managed versions to concrete versions in published POM
            versionMapping {
                usage("java-api") { fromResolutionOf("runtimeClasspath") }
                usage("java-runtime") { fromResolutionResult() }
            }
        }
    }
}

tasks.withType<GenerateModuleMetadata> {
    // Shared modules inherit enforcedPlatform from root subprojects block for version
    // alignment, but don't depend on Quarkus at runtime. Suppress the validation.
    suppressedValidationErrors.add("enforced-platform")
}
