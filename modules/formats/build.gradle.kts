plugins {
    `java-library`
    id("com.google.protobuf")
}

val commonsCompressVersion: String by project
val tikaVersion: String by project
val protobufVersion: String by project

dependencies {
    api(project(":shared:utils")) // exposes BinaryData/Buffer/ContentHash

    // CDI for registry and bean discovery
    implementation("io.quarkus:quarkus-arc")

    // Archive formats
    implementation("org.apache.commons:commons-compress:$commonsCompressVersion")

    // Universal format detection (Tika)
    implementation("org.apache.tika:tika-core:$tikaVersion")
    implementation("org.apache.tika:tika-parsers-standard-package:$tikaVersion")

    // Serialization
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}
