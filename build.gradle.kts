import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val localIdePath = providers.gradleProperty("localIdePath")
    .orElse(providers.environmentVariable("JETBRAINS_IDE_PATH"))

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    intellijPlatform {
        if (localIdePath.isPresent) {
            local(localIdePath)
        } else {
            create(
                providers.gradleProperty("platformType").get(),
                providers.gradleProperty("platformVersion").get(),
            )
        }
        bundledPlugin("org.jetbrains.plugins.terminal")
        pluginVerifier()
    }

    testImplementation(kotlin("test"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

intellijPlatform {
    pluginConfiguration {
        name = "Codex Session Tabs"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }

        vendor {
            name = "Ryan Tate"
            url = "https://github.com/ryantate-amp"
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    buildSearchableOptions {
        enabled = false
    }
}
