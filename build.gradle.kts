import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21" // reads the Kotlin 2.4 stdlib bundled with 2026.2
    id("org.jetbrains.intellij.platform") version "2.19.0" // needs Gradle 9+
}

group = "io.github.mrglasses"
// Bump on every pushed change: the bridge logs it first, so you can tell which build is installed.
version = "0.15.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Pass your installed RustRover to compile against it instead of downloading an SDK:
//   ./gradlew buildPlugin -PrustroverPath="C:\Program Files\JetBrains\RustRover 2026.2"
val rustroverPath: String? = providers.gradleProperty("rustroverPath").orNull

dependencies {
    intellijPlatform {
        // "RR" = RustRover. Matches the user's IDE (2026.2.4, build 262): needs Java 25 to build.
        if (rustroverPath != null) local(rustroverPath) else create("RR", "2026.2.4")
        // Gson and pty4j come from the platform.
        bundledPlugin("org.jetbrains.plugins.terminal") // terminal tabs and the env customizer
        bundledPlugin("com.intellij.modules.jcef") // since 2026.2 JCEF is a bundled plugin, not core (chat panel)
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

// claude-ui.properties gets the version at build time (PluginInfo reads it; no internal IntelliJ API needed).
tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("claude-ui.properties") { expand("version" to v) }
    // The chat panel bundle, built by `npm ci && npm run build` in webview/ (CI does it before Gradle).
    from("webview/dist") { into("webview") }
    val panelJs = file("webview/dist/panel.js")
    doFirst {
        check(panelJs.isFile) { "webview/dist/panel.js is missing: run `npm ci && npm run build` in webview/ first" }
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

// No jvmToolchain(): the build uses whichever JVM runs Gradle. IntelliJ Platform 2026.2 needs Java 25:
// CI sets up Temurin 25; locally, RustRover 2026.2's bundled JBR is Java 25 too (see BUILD.md).
