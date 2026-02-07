plugins {
    id("io.quarkus")
}

dependencies {
    implementation(project(":shared:types"))
    implementation(project(":modules:core"))
    implementation(project(":modules:api"))

    // Test
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.quarkus:quarkus-jdbc-postgresql-deployment")
}
