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
