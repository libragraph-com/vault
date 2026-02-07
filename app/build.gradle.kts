plugins {
    id("io.quarkus")
}

dependencies {
    implementation(project(":shared:types"))
    implementation(project(":shared:utils"))
    implementation(project(":modules:core"))
    implementation(project(":modules:formats"))
    implementation(project(":modules:api"))

    // Test
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.quarkus:quarkus-jdbc-postgresql-deployment")
    testImplementation("org.testcontainers:minio:1.20.4")
}

// Forward vault.test.* project properties to the Quarkus test JVM.
// Must be configured here (after io.quarkus plugin) so the plugin
// doesn't override the test task settings.
//
// Gradle -P flag             → JVM system property (kebab-case for Quarkus config)
// vault.test.tenantId        → vault.test.tenant-id
// vault.test.resetTenant     → vault.test.reset-tenant
// vault.test.profile         → quarkus.test.profile
tasks.withType<Test> {
    val propMappings = mapOf(
        "vault.test.tenantId"     to "vault.test.tenant-id",
        "vault.test.resetTenant"  to "vault.test.reset-tenant",
        "vault.test.profile"      to "quarkus.test.profile",
    )
    propMappings.forEach { (gradleProp, sysProp) ->
        val value = project.findProperty(gradleProp) as String?
        inputs.property(gradleProp, value ?: "")
        if (value != null) {
            systemProperty(sysProp, value)
        }
    }
}
