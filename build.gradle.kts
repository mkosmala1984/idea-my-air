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
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.core")
        bundledPlugin("com.intellij.platform.vcs")
        // Optional at runtime (my-air-branch-diff.xml); build it first with idea-branch-diff's buildPlugin.
        localPlugin("E:/dev/projects/idea-branch-diff/build/distributions/branch-diff-tree-0.1.0.zip")
    }
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
}
