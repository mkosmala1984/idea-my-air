plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.myair"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("E:/dev/apps/jetbrains/IntelliJ IDEA Ultimate")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.terminal.frontend")
        bundledPlugin("com.intellij.mcpServer")
        // JCEF moved into the bundled plugin "Web Browser (JCEF)" (com.intellij.modules.jcef); the canvas tab uses it.
        bundledModule("intellij.platform.ui.jcef")
        bundledModule("intellij.libraries.jcef")
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.core")
        bundledPlugin("com.intellij.platform.vcs")
        // Optional at runtime (my-air-branch-diff.xml); build it first with idea-branch-diff's buildPlugin.
        localPlugin("E:/dev/projects/personal/idea-branch-diff/build/distributions/branch-diff-tree-0.1.0.zip")
    }
    // The conversation index (search/): SQLite with FTS5. The IDE's own copy is a library module no plugin can depend on.
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // ClaudePrintTest removes an inherited CLAUDE_CONFIG_DIR from a child's environment, so the test JVM must have one.
    environment("CLAUDE_CONFIG_DIR", "E:/inherited-claude-config")
    // CanvasProtocolTest reads the contract it shares with canvas-web's tests from there (tests run in the project dir).
    inputs.file("canvas-web/src/protocol.json")
}
