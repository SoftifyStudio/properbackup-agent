plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":properbackup-shared"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

application {
    mainClass.set("pl.danielniemiec.properbackup.agent.AgentMain")
}

tasks.test {
    useJUnitPlatform()
}

fun npmExecutable(): String =
    if (System.getProperty("os.name", "").lowercase().contains("windows")) "npm.cmd" else "npm"

tasks.register<Exec>("properbackupShellElectronInstall") {
    group = "distribution"
    description =
        "Instaluje zależności Electron shell (properbackup-shell-electron/node_modules) + buduje installDist agenta."
    dependsOn(tasks.named("installDist"))
    workingDir = rootProject.file("properbackup-shell-electron")
    commandLine(npmExecutable(), "install")
}

tasks.register("properbackupDesktopAllSetup") {
    group = "distribution"
    description = "Jedno miejsce: przygotowuje agenta JVM + Electron UI do uruchomienia."
    dependsOn(tasks.named("properbackupShellElectronInstall"))
}

tasks.register<Exec>("properbackupDesktopRun") {
    group = "application"
    description = "Jedno miejsce: uruchamia Electron UI + agent JVM (cross-platform desktop)."
    dependsOn(tasks.named("properbackupDesktopAllSetup"))
    workingDir = rootProject.file("properbackup-shell-electron")
    environment(
        "PROPERBACKUP_AGENT_HOME",
        rootProject.file("properbackup-agent-jvm/build/install/properbackup-agent-jvm").absolutePath,
    )
    environment("PROPERBACKUP_CONF_ROOT", rootProject.file(".").absolutePath)
    commandLine(npmExecutable(), "start")
}

tasks.register<Exec>("properbackupDesktopRunWebDev") {
    group = "application"
    description =
        "Electron + agent; panel = lokalny Vite (`properbackup-web`, domyślnie http://127.0.0.1:5173). Uruchom wcześniej: `cd properbackup-web && npm run dev`."
    dependsOn(tasks.named("properbackupDesktopAllSetup"))
    workingDir = rootProject.file("properbackup-shell-electron")
    environment(
        "PROPERBACKUP_AGENT_HOME",
        rootProject.file("properbackup-agent-jvm/build/install/properbackup-agent-jvm").absolutePath,
    )
    environment("PROPERBACKUP_CONF_ROOT", rootProject.file(".").absolutePath)
    environment(
        "PROPERBACKUP_PANEL_URL",
        project.findProperty("properbackupWebDevUrl")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "http://127.0.0.1:5173",
    )
    commandLine(npmExecutable(), "start")
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "pl.danielniemiec.properbackup.agent.AgentMain")
    }
}

/**
 * Bundled JRE distribution — produces a self-contained archive with a minimal JRE
 * so end users don't need to install Java. Output: build/properbackup-agent-dist/
 *
 * Usage after extraction:  ./properbackup-agent --activate TOKEN
 */
tasks.register("jlinkDist") {
    group = "distribution"
    description = "Build a self-contained distribution with bundled minimal JRE (jlink)."
    dependsOn(tasks.named("installDist"))

    val distDir = layout.buildDirectory.dir("properbackup-agent-dist").get().asFile
    val jlinkDir = layout.buildDirectory.dir("jlink-jre").get().asFile
    val modules = "java.base,java.desktop,java.net.http,java.sql,jdk.management,jdk.unsupported"

    doLast {
        if (distDir.exists()) distDir.deleteRecursively()
        if (jlinkDir.exists()) jlinkDir.deleteRecursively()

        val javaHome = System.getProperty("java.home")
        val jlinkBin = file("$javaHome/bin/jlink")
        if (!jlinkBin.exists()) {
            throw GradleException("jlink not found at $jlinkBin — requires JDK (not JRE)")
        }

        exec {
            commandLine(
                jlinkBin.absolutePath,
                "--add-modules", modules,
                "--strip-debug",
                "--no-man-pages",
                "--no-header-files",
                "--compress", "zip-6",
                "--output", jlinkDir.absolutePath,
            )
        }

        distDir.mkdirs()
        val libDir = distDir.resolve("lib")
        libDir.mkdirs()

        @Suppress("DEPRECATION")
        val installDir = tasks.named<org.gradle.api.tasks.Sync>("installDist").get().destinationDir
        installDir.resolve("lib").listFiles()?.forEach { jar ->
            jar.copyTo(libDir.resolve(jar.name))
        }

        jlinkDir.copyRecursively(distDir.resolve("jre"))

        // ensure jre binaries are executable after copy
        distDir.resolve("jre/bin").listFiles()?.forEach { it.setExecutable(true) }

        val mainCls = application.mainClass.get()

        // Unix launcher
        distDir.resolve("properbackup-agent").apply {
            writeText(
                """
                |#!/bin/sh
                |SCRIPT_DIR="$(cd "$(dirname "${'$'}0")" && pwd)"
                |exec "${'$'}SCRIPT_DIR/jre/bin/java" -cp "${'$'}SCRIPT_DIR/lib/*" $mainCls "${'$'}@"
                """.trimMargin()
            )
            setExecutable(true)
        }

        // Windows launcher
        distDir.resolve("properbackup-agent.bat").writeText(
            """
            |@echo off
            |set SCRIPT_DIR=%~dp0
            |"%SCRIPT_DIR%jre\bin\java.exe" -cp "%SCRIPT_DIR%lib\*" $mainCls %*
            """.trimMargin()
        )

        println("Distribution built: ${distDir.absolutePath}")
        println("  Unix:    ./properbackup-agent --activate <TOKEN>")
        println("  Windows: properbackup-agent.bat --activate <TOKEN>")
    }
}

/** macOS only: `installDist` + `jpackage` → `build/jpackage-agent/ProperBackup Agent.app` (and helpers). */
tasks.register<Exec>("jpackageMacAgentApp") {
    group = "distribution"
    description = "Build a macOS .app for the headless agent (requires jpackage from JDK 21+)."
    dependsOn(tasks.named("installDist"))
    onlyIf {
        System.getProperty("os.name", "").lowercase().let { it.contains("mac") || it.contains("darwin") }
    }
    doFirst {
        @Suppress("DEPRECATION")
        val installDir = tasks.named<org.gradle.api.tasks.Sync>("installDist").get().destinationDir
        val libDir = installDir.resolve("lib")
        val dest = layout.buildDirectory.dir("jpackage-agent").get().asFile
        if (dest.exists()) {
            dest.deleteRecursively()
        }
        dest.mkdirs()
        val mainJarName = tasks.jar.get().archiveFileName.get()
        val mainCls = application.mainClass.get()
        commandLine(
            "jpackage",
            "--type", "app-image",
            "--name", "ProperBackup Agent",
            "--input", libDir.absolutePath,
            "--main-jar", mainJarName,
            "--main-class", mainCls,
            "--dest", dest.absolutePath,
        )
    }
}
