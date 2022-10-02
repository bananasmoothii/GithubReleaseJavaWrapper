import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.20"
    id("com.github.johnrengelman.shadow") version "7.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.7.20"
}

group = "fr.bananasmoothii"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.kohsuke:github-api:1.313")

    // for kotlinx.serialization with yaml files
    implementation("com.charleskorn.kaml:kaml:0.47.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.getByName<ShadowJar>("shadowJar") {
    manifest {
        attributes["Main-Class"] = "fr.bananasmoothii.githubreleasejavawrapper.MainKt"
    }
}