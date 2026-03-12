import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Shared KMM module (desktopMain source set)
    implementation(project(":shared"))

    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // SQLite via Exposed ORM
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("org.jetbrains.exposed:exposed-core:0.45.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.45.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.45.0")

    // Ktor WebSocket client for relay transport
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-websockets:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.google.zxing:javase:3.5.2")

    // Signal Protocol (desktop JVM)
    implementation("org.signal:libsignal-client:0.44.0")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.9")
}

compose.desktop {
    application {
        mainClass = "com.meshcipher.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Exe)
            packageName = "MeshCipher"
            packageVersion = "1.0.0"
            description = "Encrypted peer-to-peer messenger"
            vendor = "MeshCipher"

            modules("java.sql", "java.naming", "java.security.jgss", "jdk.security.auth")

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                packageName = "meshcipher"
                debMaintainer = "hello@meshcipher.com"
                menuGroup = "Network;Chat"
            }

            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "MeshCipher"
                upgradeUuid = "6e8f7a1b-2c3d-4e5f-a6b7-c8d9e0f1a2b3"
            }
        }
    }
}
