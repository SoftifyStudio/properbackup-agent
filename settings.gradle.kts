pluginManagement {
    plugins {
        kotlin("jvm") version "1.9.23"
        kotlin("multiplatform") version "1.9.23"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "properbackup-agent"

include(":properbackup-shared")
project(":properbackup-shared").projectDir = file("../properbackup-shared")
