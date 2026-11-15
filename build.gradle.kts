plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "cz.jarda"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2023.1.7")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Java plugin provides CompilationStatusListener (build events). It is an
        // optional dependency at runtime (see plugin.xml), but needed at compile time.
        bundledPlugin("com.intellij.java")
    }

    // JUnit 4 for both plain unit tests and the platform test fixtures.
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "231"
            // No upper bound: otherwise the Gradle plugin defaults until-build to "231.*"
            // (the compile target) and newer IDEs refuse to load the plugin.
            untilBuild = provider { null }
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---- Demo soundpack generator (dev tool, not shipped in the plugin) ----
// Renders src/soundgen/.../DemoSoundCatalog.kt through the JDK's Gervill MIDI synth into
// src/main/resources/soundpacks/demo-midi (WAV + soundpack.json) and src/soundgen/midi (.mid).
val soundgen: SourceSet by sourceSets.creating

dependencies {
    // The Kotlin stdlib is not added automatically (kotlin.stdlib.default.dependency=false).
    "soundgenImplementation"(kotlin("stdlib"))
}

val generateDemoSounds by tasks.registering(JavaExec::class) {
    group = "soundplugin"
    description = "Regenerates the bundled demo-midi soundpack (WAV, MIDI sources, manifest)."
    classpath = soundgen.runtimeClasspath
    mainClass.set("cz.jarda.soundplugin.soundgen.GenerateDemoPackKt")
    // Offline rendering uses AudioSynthesizer.openStream from a non-exported JDK package.
    jvmArgs("--add-opens=java.desktop/com.sun.media.sound=ALL-UNNAMED")
    args(
        layout.projectDirectory.dir("src/main/resources/soundpacks/demo-midi").asFile.absolutePath,
        layout.projectDirectory.dir("src/soundgen/midi").asFile.absolutePath,
    )
    providers.gradleProperty("soundfont").orNull?.let { args("--soundfont", it) }
}
