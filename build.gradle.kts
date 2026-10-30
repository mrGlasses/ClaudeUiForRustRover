import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "io.github.mrglasses"
// Bump on every pushed change: the bridge logs it first, so you can tell which build is installed.
version = "0.11.1"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Pass your installed RustRover to compile against it instead of downloading an SDK:
//   ./gradlew buildPlugin -PrustroverPath="C:\Program Files\JetBrains\RustRover 2025.2"
val rustroverPath: String? = providers.gradleProperty("rustroverPath").orNull

dependencies {
    intellijPlatform {
        if (rustroverPath != null) local(rustroverPath) else create("RR", "2025.2") // "RR" = RustRover
        // Gson and pty4j come from the platform.
        bundledPlugin("org.jetbrains.plugins.terminal") // only if you reuse the IDE terminal widget
    }
    // Small standalone WS server, no Netty clash with the platform.
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // Plain JUnit 4 tests for the protocol layer (no IDE test framework needed).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginVerification {
        ides { recommended() }
    }
}

tasks.test {
    // Full assertion messages in the CI log, so failures can be pasted back.
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// No jvmToolchain(): it would force a full JDK 21 (or a download). The build uses whichever JVM runs Gradle,
// e.g. the one bundled with RustRover (see BUILD.md) or the one in CI.
