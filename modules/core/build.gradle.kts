plugins {
    `java-library`
}

val jdbiVersion: String by project
val minioVersion: String by project
val commonsCodecVersion: String by project
val protobufVersion: String by project

dependencies {
    implementation(project(":shared:types"))
    implementation(project(":shared:utils"))

    // Quarkus CDI + config
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-config-yaml")

    // Database
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-agroal")
    implementation("io.quarkus:quarkus-flyway")
    api("org.jdbi:jdbi3-core:$jdbiVersion")
    implementation("org.jdbi:jdbi3-sqlobject:$jdbiVersion")
    implementation("org.jdbi:jdbi3-postgres:$jdbiVersion")
    implementation("org.jdbi:jdbi3-jackson2:$jdbiVersion")

    // Reactive (Mutiny)
    implementation("io.smallrye.reactive:mutiny")

    // Object storage
    implementation("io.minio:minio:$minioVersion")

    // Health checks
    implementation("io.quarkus:quarkus-smallrye-health")

    // Content hashing + serialization (ready for future use)
    implementation("commons-codec:commons-codec:$commonsCodecVersion") // includes BLAKE3
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
}
