// SKETCH — not compiled. Versions are placeholders; bump to current before first build.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        create("RR", "2025.2") // "RR" = RustRover. Gson and pty4j come from the platform.
        bundledPlugin("org.jetbrains.plugins.terminal") // only if you reuse the IDE terminal widget
    }
    // Small standalone WS server, no Netty clash with the platform.
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}

kotlin { jvmToolchain(21) }
