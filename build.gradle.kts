plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
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

/**
 * jlinkDist: build a self-contained distribution with portable JRE (jlink) + agent.
 * Result: build/jlink-dist/properbackup-agent-<version>.tar.gz (<70MB target).
 */
tasks.register("jlinkDist") {
    group = "distribution"
    description = "Build self-contained tar.gz with portable JRE (jlink) + agent JAR. No system Java needed."
    dependsOn(tasks.named("shadowJar"))

    doLast {
        val shadowJarFile = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
            .get().archiveFile.get().asFile
        val jlinkDir = layout.buildDirectory.dir("jlink-dist").get().asFile
        val imageDir = File(jlinkDir, "properbackup-agent")
        if (imageDir.exists()) imageDir.deleteRecursively()
        jlinkDir.mkdirs()

        val javaHome = System.getProperty("java.home") ?: error("java.home not set")
        val jlinkBin = File(javaHome, "bin/jlink").absolutePath
        val jmodDir = File(javaHome, "jmods")
        if (!jmodDir.exists()) error("jmods directory not found at $jmodDir — need a full JDK, not a JRE")

        val modules = listOf(
            "java.base",
            "java.logging",
            "java.net.http",
            "java.management",
            "java.desktop",
            "java.naming",
            "java.sql",
            "jdk.unsupported",
            "jdk.crypto.ec",
        )

        val jlinkResult = exec {
            commandLine(
                jlinkBin,
                "--add-modules", modules.joinToString(","),
                "--strip-debug",
                "--no-man-pages",
                "--no-header-files",
                "--compress=zip-6",
                "--output", File(imageDir, "jre").absolutePath,
            )
            isIgnoreExitValue = true
        }
        if (jlinkResult.exitValue != 0) error("jlink failed with exit code ${jlinkResult.exitValue}")

        val libDir = File(imageDir, "lib")
        libDir.mkdirs()
        shadowJarFile.copyTo(File(libDir, "properbackup-agent.jar"), overwrite = true)

        val launcherScript = File(imageDir, "properbackup-agent")
        launcherScript.writeText(
            """
            |#!/bin/sh
            |SCRIPT_DIR="$(cd "$(dirname "${'$'}0")" && pwd)"
            |JAVA_BIN="${'$'}SCRIPT_DIR/jre/bin/java"
            |exec "${'$'}JAVA_BIN" -Djava.awt.headless=true \
            |  -jar "${'$'}SCRIPT_DIR/lib/properbackup-agent.jar" \
            |  --headless "${'$'}@"
            """.trimMargin()
        )
        launcherScript.setExecutable(true)

        val installScript = File(imageDir, "install-service.sh")
        val serviceScriptSrc = file("scripts/install-service.sh")
        if (serviceScriptSrc.exists()) {
            serviceScriptSrc.copyTo(installScript, overwrite = true)
        } else {
            installScript.writeText("#!/bin/sh\necho 'install-service.sh not bundled'\nexit 1\n")
        }
        installScript.setExecutable(true)

        val tarFile = File(jlinkDir, "properbackup-agent.tar.gz")
        exec {
            workingDir = jlinkDir
            commandLine("tar", "czf", tarFile.absolutePath, "properbackup-agent")
        }

        val sizeMb = tarFile.length() / (1024.0 * 1024.0)
        println("jlinkDist: ${tarFile.absolutePath} (${String.format("%.1f", sizeMb)} MB)")
        if (sizeMb > 70) {
            logger.warn("WARNING: tar.gz is ${String.format("%.1f", sizeMb)} MB — exceeds 70MB target!")
        }
    }
}
