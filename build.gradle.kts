plugins {
    java
}

val quarkusVersion: String by project

subprojects {
    group = rootProject.findProperty("projectGroup") as String
    version = rootProject.findProperty("projectVersion") as String

    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        // Quarkus BOM for all subprojects
        implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))

        // Common test deps
        testImplementation("io.quarkus:quarkus-junit5")
        testImplementation("org.assertj:assertj-core:3.27.3")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21) // Quarkus 3.x bytecode target
        options.compilerArgs.addAll(listOf("-parameters"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }
}
