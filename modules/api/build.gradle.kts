dependencies {
    implementation(project(":shared:types"))
    implementation(project(":modules:core"))

    // Quarkus REST
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
}
