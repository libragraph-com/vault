// Pure Java utilities — no CDI, no Quarkus.
// Houses ContentHash and the buffer layer (BinaryData, Buffer, RamBuffer).

plugins {
    `java-library`
}

val commonsCodecVersion: String by project

dependencies {
    api("commons-codec:commons-codec:$commonsCodecVersion") // BLAKE3 (exposed via Buffer/BinaryData → ContentHash)
}
