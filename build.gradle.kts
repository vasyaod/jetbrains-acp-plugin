import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

allprojects {
    repositories {
        mavenCentral()
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // The oldest IDE we support (2024.2) ships Kotlin 2.0's stdlib.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        // Use the platform interfaces' own default methods instead of generating bridge
        // overrides (which the plugin verifier reports as overriding internal/deprecated API).
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    // The plain-Kotlin ACP client core (no IntelliJ classes), bundled into the plugin.
    implementation(project(":core"))

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        // Headless IDE tests of the IntelliJ layer (documents, undo, processes).
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    // Searchable options need a headless IDE launch; the settings page is tiny.
    buildSearchableOptions = false

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // The oldest supported line, the compile target, and the newest release.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.6")
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion").get())
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
}

tasks {
    // `./gradlew test` runs the plain-JUnit core tests and the headless IDE tests.
    test {
        dependsOn(":core:test")
    }
}
