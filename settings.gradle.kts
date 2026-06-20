pluginManagement {
    plugins {
        kotlin("jvm") version "1.9.23"
        kotlin("multiplatform") version "1.9.23"
        id("com.github.johnrengelman.shadow") version "8.1.1"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "properbackup-agent"

// Expected layout: parent dir contains both properbackup-agent/ and properbackup-shared/
val sharedDir = file("../properbackup-shared")
if (sharedDir.exists()) {
    include(":properbackup-shared")
    project(":properbackup-shared").projectDir = sharedDir
}
