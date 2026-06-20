package pl.danielniemiec.properbackup.agent

import pl.danielniemiec.properbackup.agent.activation.AccessCodeGenerator
import pl.danielniemiec.properbackup.agent.activation.ActivationClient
import pl.danielniemiec.properbackup.agent.activation.GlobalConfigWriter
import pl.danielniemiec.properbackup.agent.browser.SystemBrowserLauncher
import pl.danielniemiec.properbackup.agent.config.ConfigLoader
import pl.danielniemiec.properbackup.agent.config.GlobalConfig
import pl.danielniemiec.properbackup.agent.config.ServiceConfig
import pl.danielniemiec.properbackup.agent.packer.TarGzPacker
import pl.danielniemiec.properbackup.agent.scanner.DifferentialScanner
import pl.danielniemiec.properbackup.agent.scanner.MetadataCache
import pl.danielniemiec.properbackup.agent.service.ServiceInstaller
import pl.danielniemiec.properbackup.agent.transport.BufferUploader
import pl.danielniemiec.properbackup.agent.transport.HeartbeatClient
import pl.danielniemiec.properbackup.agent.transport.JwtClient
import pl.danielniemiec.properbackup.agent.transport.SubscriptionExpiredException
import pl.danielniemiec.properbackup.agent.logging.RemoteTelemetry
import pl.danielniemiec.properbackup.agent.ui.AgentPanelUrls
import pl.danielniemiec.properbackup.agent.util.AgentInstanceLock
import pl.danielniemiec.properbackup.agent.util.GuiSingletonLock
import pl.danielniemiec.properbackup.agent.util.JvmLaunch
import pl.danielniemiec.properbackup.agent.util.MachineIdentity
import pl.danielniemiec.properbackup.agent.util.StartupTrace
import pl.danielniemiec.properbackup.agent.recovery.RecoveryExecutor
import pl.danielniemiec.properbackup.agent.recovery.RecoveryPoller
import pl.danielniemiec.properbackup.core.codec.ProperCodec
import pl.danielniemiec.properbackup.core.crypto.ProperCrypto
import java.awt.GraphicsEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.Optional
import kotlin.system.exitProcess

object AgentMain {

    private const val OPEN_PANEL_URL = "--open-panel-url"
    /** Natywna powłoka (np. macOS WKWebView): nie odpalaj drugiego JVM / przeglądarki — URL ładuje host. */
    private const val EMBEDDED_PANEL = "--embedded-panel"
    /** Wypisz jedną linię `PANEL_URL=…` i zakończ (0). Do odpytywania URL z powłoki bez duplikacji logiki YAML. */
    private const val PRINT_PANEL_URL = "--print-panel-url"

    /** When true, GUI mode skips spawning browser / second JVM (Compose desktop loads the URL). */
    @Volatile
    @JvmField
    var skipPanelSpawnForEmbeddedDesktop: Boolean = false

    @JvmStatic
    fun main(args: Array<String>) {
        StartupTrace.initFromClassJar(AgentMain::class.java)
        StartupTrace.line(
            "main: start java.home=${System.getProperty("java.home")} " +
                "headless=${GraphicsEnvironment.isHeadless()} args=[${args.joinToString(" ")}]",
        )
        exitIfOriginalThinShadeBackupJar()
        for (i in args.indices) {
            if (OPEN_PANEL_URL == args[i]) {
                if (i + 1 >= args.size) {
                    System.err.println("Brak URL po $OPEN_PANEL_URL")
                    exitProcess(2)
                }
                SystemBrowserLauncher.openUrl(args[i + 1])
                return
            }
        }

        var globalYml = Path.of("conf.d/global.yml")
        var confDir = Path.of("conf.d")
        var dryRun = false
        var activateToken: String? = null
        var activationGui = false
        var once = false
        var explicitHeadless = false
        var embeddedPanel = false
        var printPanelUrl = false
        var installService = false
        var uninstallService = false
        var serviceStatus = false
        var showVersion = false

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--config" -> globalYml = Path.of(args[++i])
                "--conf-dir" -> confDir = Path.of(args[++i])
                "--dry-run" -> dryRun = true
                "--gui" -> activationGui = true
                "--once" -> once = true
                "--headless" -> explicitHeadless = true
                EMBEDDED_PANEL -> embeddedPanel = true
                PRINT_PANEL_URL -> printPanelUrl = true
                "--install-service", "install-service" -> installService = true
                "--uninstall-service", "uninstall-service" -> uninstallService = true
                "--service-status", "service-status" -> serviceStatus = true
                "--version" -> showVersion = true
                "--activate" -> {
                    if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                        activateToken = args[++i]
                    } else {
                        activationGui = true
                    }
                }
                else -> { /* ignore */ }
            }
            i++
        }

        // --version: print version and exit
        if (showVersion) {
            println("ProperBackup Agent v${agentVersion()}")
            exitProcess(0)
        }

        // Service management commands
        if (installService) {
            handleInstallService(globalYml, confDir)
            return
        }
        if (uninstallService) {
            val result = ServiceInstaller.uninstall()
            println(result.message)
            exitProcess(if (result.success) 0 else 1)
        }
        if (serviceStatus) {
            println("Service status: ${ServiceInstaller.status()}")
            exitProcess(0)
        }

        if (isExplorerStyleJarLaunch(args) && !explicitHeadless) {
            selfJarPath().ifPresent { jar ->
                configRootBesideJarOrApp(jar).ifPresent { base ->
                    globalYml = base.resolve("conf.d").resolve("global.yml")
                    confDir = base.resolve("conf.d")
                }
            }
        }

        if (printPanelUrl) {
            val url = try {
                AgentPanelUrls.buildAgentLandingFromConfig(globalYml, activateToken)
            } catch (e: Exception) {
                System.err.println("[agent] --print-panel-url: ${e.message ?: e}")
                exitProcess(1)
            }
            println("PANEL_URL=$url")
            exitProcess(0)
        }

        val implicitGui = implicitGuiFromExplorer(args, explicitHeadless)
        val guiMode = activationGui || implicitGui
        StartupTrace.line(
            "main: guiMode=$guiMode implicitGui=$implicitGui activationGui=$activationGui " +
                "globalYml=${globalYml.toAbsolutePath()} confDir=${confDir.toAbsolutePath()}",
        )

        if (guiMode) {
            if (embeddedPanel) {
                skipPanelSpawnForEmbeddedDesktop = true
                StartupTrace.line("main: embeddedPanel=true (native shell / brak spawn panelu)")
            }
            runGuiAndAgent(globalYml, confDir, dryRun, once, activateToken)
            return
        }

        if (!activateToken.isNullOrBlank()) {
            val global = ConfigLoader.loadGlobal(globalYml)
            val bufferUrl = global.agent.buffer_url.replace("/+$".toRegex(), "")
            val client = ActivationClient(bufferUrl)
            val result = client.activate(activateToken, true)
            GlobalConfigWriter.setServerId(globalYml, result.serverId)
            val panelUrl = AgentPanelUrls.guessPanelFromBuffer(bufferUrl)
            val machineId = MachineIdentity.getOrCreate(MachineIdentity.defaultDeviceIdFile(globalYml))
            val q = linkedMapOf(
                "agent" to "1",
                "machineId" to machineId,
                "unlinked" to "0",
                "serverId" to result.serverId,
            )
            var open = AgentPanelUrls.authLanding(panelUrl, q)
            result.session.ifPresent { s ->
                open = AgentPanelUrls.withSessionHash(
                    open,
                    mapOf(
                        "token" to s.token,
                        "email" to s.email,
                        "id" to s.id,
                        "isSystemAdmin" to s.isSystemAdmin,
                    ),
                )
            }
            println("[activation] Opening panel...")
            SystemBrowserLauncher.openUrl(open)
            return
        }

        // First-run: if server_id is blank, run access code activation flow
        run {
            val g = ConfigLoader.loadGlobal(globalYml)
            if (g.agent.server_id.isBlank()) {
                runAccessCodeActivation(globalYml, confDir)
                return
            }
        }

        val lockFile = globalYml.resolveSibling(".agent.lock")
        AgentInstanceLock.tryAcquire(lockFile)?.use {
            if (once) {
                runOneBackupCycle(globalYml, confDir, dryRun)
                println("Agent run complete.")
            } else {
                runAgentLoop(globalYml, confDir, dryRun)
            }
        } ?: run {
            StartupTrace.line("acquireOrExit: zajęty lock — inna instancja: ${lockFile.toAbsolutePath()}")
            System.err.println(
                "ProperBackup agent jest już uruchomiony (inna instancja trzyma plik lock: " +
                    "${lockFile.toAbsolutePath()}).",
            )
            exitProcess(1)
        }
    }

    @JvmStatic
    fun isExplorerStyleJarLaunch(args: Array<String>): Boolean =
        args.all { a -> a.isNullOrEmpty() || implicitGuiJarLaunchNoiseArg(a) }

    @JvmStatic
    fun implicitGuiJarLaunchNoiseArg(a: String): Boolean = when {
        a.startsWith("-psn_") -> true
        a == "-XstartOnFirstThread" -> true
        a.startsWith("-Xdock:") -> true
        a.startsWith("-Dapple.awt.UIElement") -> true
        else -> false
    }

    @JvmStatic
    fun configRootBesideJarOrApp(jarFile: Path): Optional<Path> {
        val abs = jarFile.toAbsolutePath().normalize()
        var cur = abs.parent
        while (cur != null) {
            val fn = cur.fileName
            if (fn != null && fn.toString().endsWith(".app")) {
                return Optional.ofNullable(cur.parent)
            }
            cur = cur.parent
        }
        return Optional.ofNullable(abs.parent)
    }

    @JvmStatic
    fun isShadeOriginalThinBackupJarName(fileName: String?): Boolean {
        if (fileName == null) return false
        val low = fileName.lowercase(Locale.ROOT)
        return low.endsWith(".jar") && low.startsWith("original-")
    }

    @JvmStatic
    fun selfJarPath(): Optional<Path> {
        return try {
            val loc = AgentMain::class.java.protectionDomain.codeSource?.location ?: return Optional.empty()
            val p = Paths.get(loc.toURI())
            if (!p.toString().lowercase(Locale.ROOT).endsWith(".jar")) Optional.empty() else Optional.of(p)
        } catch (e: Exception) {
            Optional.empty()
        }
    }

    @JvmStatic
    fun resolveEnv(value: String?): String {
        if (value == null) return ""
        if (value.startsWith("\${") && value.endsWith("}")) {
            val v = value.substring(2, value.length - 1)
            return System.getenv(v) ?: ""
        }
        return value
    }

    private fun implicitGuiFromExplorer(args: Array<String>, explicitHeadless: Boolean): Boolean {
        if (explicitHeadless || !isExplorerStyleJarLaunch(args)) return false
        if (System.getenv("PROPERBACKUP_FORCE_HEADLESS").toBoolean()) return false
        if (System.getenv("CI").toBoolean()) return false
        return true
    }

    private fun acquireOrExit(lockFile: Path): AgentInstanceLock {
        return AgentInstanceLock.tryAcquire(lockFile) ?: run {
            StartupTrace.line("acquireOrExit: zajęty lock — inna instancja: ${lockFile.toAbsolutePath()}")
            System.err.println(
                "ProperBackup agent jest już uruchomiony (inna instancja trzyma plik lock: " +
                    "${lockFile.toAbsolutePath()}).",
            )
            exitProcess(1)
        }
    }

    private fun runGuiAndAgent(
        globalYml: Path,
        confDir: Path,
        dryRun: Boolean,
        once: Boolean,
        presetActivationKey: String?,
    ) {
        val gy = globalYml
        val cd = confDir
        val dr = dryRun

        GuiSingletonLock.tryAcquire()?.use {
            val lockFile = gy.resolveSibling(".agent.lock")
            StartupTrace.line("runGuiAndAgent: lockFile=${lockFile.toAbsolutePath()} once=$once")
            acquireOrExit(lockFile).use {
                StartupTrace.line("runGuiAndAgent: lock acquired, spawning panel…")
                if (!skipPanelSpawnForEmbeddedDesktop) {
                    spawnPanelInSeparateJvm(gy, presetActivationKey)
                } else {
                    StartupTrace.line("runGuiAndAgent: skipPanelSpawnForEmbeddedDesktop=true")
                }
                StartupTrace.line("runGuiAndAgent: panel spawn done, starting agent loop/cycle…")
                if (once) {
                    runOneBackupCycle(gy, cd, dr)
                    println("Agent run complete.")
                } else {
                    runAgentLoop(gy, cd, dr)
                }
            }
        } ?: run {
            StartupTrace.line(
                "runGuiAndAgent: GUI singleton zajęty — inna instancja agenta z panelem (" +
                    GuiSingletonLock.lockFilePath() + ").",
            )
            System.err.println("ProperBackup agent z panelem jest już uruchomiony.")
            notifyMacGuiSingletonBlocked()
            exitProcess(0)
        }
    }

    private fun notifyMacGuiSingletonBlocked() {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        if (!os.contains("mac") && !os.contains("darwin")) return
        try {
            ProcessBuilder(
                "/usr/bin/osascript",
                "-e",
                "display alert \"ProperBackup\" message \"Agent z panelem jest już uruchomiony.\" as informational",
            )
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (ignored: Exception) {
        }
    }

    private fun spawnPanelInSeparateJvm(globalYml: Path, presetActivationKey: String?) {
        val url = AgentPanelUrls.buildAgentLandingFromConfig(globalYml, presetActivationKey)
        StartupTrace.line("spawnPanel: landing URL gotowy (len=${url.length}).")
        val jar = selfJarPath()
        val javaBin = JvmLaunch.javaExecutablePathOrPathLookup()
        if (jar.isPresent) {
            try {
                StartupTrace.line("spawnPanel: drugi JVM: $javaBin -jar ${jar.get().toAbsolutePath().normalize()}")
                ProcessBuilder(
                    javaBin,
                    "-Djava.awt.headless=false",
                    "-jar",
                    jar.get().toAbsolutePath().normalize().toString(),
                    OPEN_PANEL_URL,
                    url,
                )
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .start()
                println("[gui] Uruchomiono osobny proces panelu (przeglądarka).")
                StartupTrace.line("PROPERBACKUP_SUCCESS spawnPanel=child_jvm_open_panel_url")
                return
            } catch (e: IOException) {
                StartupTrace.line("spawnPanel: drugi JVM IOException: ${e.message} — openUrl w tej JVM.")
                System.err.println(
                    "[gui] Nie udało się uruchomić drugiego JVM: ${e.message} — otwieram panel w tej instancji.",
                )
            }
        } else {
            StartupTrace.line("spawnPanel: brak ścieżki JAR (np. uruchomienie z IDE) — openUrl w tej JVM.")
        }
        SystemBrowserLauncher.openUrl(url)
        StartupTrace.line("spawnPanel: openUrl w tej JVM zakończone.")
    }

    private fun exitIfOriginalThinShadeBackupJar() {
        val jar = selfJarPath()
        if (!jar.isPresent) return
        val fn = jar.get().fileName.toString()
        if (!isShadeOriginalThinBackupJarName(fn)) return
        val parent = jar.get().parent
        val fatName = fn.substring("original-".length)
        val fat = parent?.resolve(fatName)
        StartupTrace.line("BŁĄD: uruchomiono cienki JAR Shade (original-): ${jar.get()}")
        System.err.println(
            "ProperBackup: „$fn” to pomocniczy plik z budowania (Maven Shade) — bez bibliotek, agent się nie uruchomi.",
        )
        if (fat != null) {
            System.err.println("W tym samym folderze użyj pełnego pliku: $fatName")
            if (Files.exists(fat)) {
                System.err.println("(Ścieżka: ${fat.toAbsolutePath()})")
            }
        }
        System.err.println("Na macOS: ProperBackup-Agent-macos.command z tego samego folderu.")
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        if ((os.contains("mac") || os.contains("darwin")) && parent != null) {
            try {
                val hint = if (fat != null) {
                    "Użyj pliku: $fatName albo ProperBackup-Agent-macos.command (w tym samym folderze)."
                } else {
                    "Użyj pełnego JAR (bez przedrostka original-) albo ProperBackup-Agent-macos.command."
                }
                val esc = hint.replace("\\", "\\\\").replace("\"", "\\\"")
                ProcessBuilder(
                    "/usr/bin/osascript",
                    "-e",
                    "display alert \"ProperBackup — zły plik JAR\" message \"$esc\" as critical",
                )
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (ignored: Exception) {
            }
        }
        exitProcess(3)
    }

    @Throws(Exception::class)
    private fun runOneBackupCycle(globalYml: Path, confDir: Path, dryRun: Boolean) {
        val global = ConfigLoader.loadGlobal(globalYml)
        val machineId = if (global.agent.server_id.isNotBlank()) {
            global.agent.server_id
        } else {
            MachineIdentity.getOrCreate(MachineIdentity.defaultDeviceIdFile(globalYml))
        }
        val telemetry = RemoteTelemetry(global, machineId)
        telemetry.agentStatus(
            "indexing",
            if (global.agent.server_id.isBlank()) {
                "Uzupełnij agent.server_id — ten sam identyfikator co „Identyfikator serwera” w panelu."
            } else {
                "Przygotowanie cyklu…"
            },
        )
        val services = ConfigLoader.loadServices(confDir)
        if (services.isEmpty()) {
            println("No enabled services found in ${confDir.toAbsolutePath()}")
            telemetry.log("Agent", -301, "No enabled services found.", "WARN")
            telemetry.agentStatus("waiting", "Brak włączonych usług w conf.d — dodaj pliki service.")
            return
        }
        println("ProperBackup Agent — ${services.size} service(s), buffer: ${global.agent.buffer_url}")
        telemetry.log("Agent", -302, "Starting backup cycle.", meta = mapOf("services" to services.size))

        val stateDir = Path.of(global.agent.state_dir)
        val password = resolveEnv(global.agent.encryption_password)
        val token = resolveEnv(global.agent.upload_token)

        val bw = global.agent.upload_bandwidth_kbps
        if (bw > 0) {
            println("Upload throttled to $bw KB/s")
        }
        try {
            BufferUploader(global.agent.buffer_url, token, bw).use { uploader ->
                if (!dryRun && global.agent.server_id.isNotBlank()) {
                    val pairsMap = linkedMapOf<String, String>()
                    for (svc in services) {
                        for (pathStr in svc.paths ?: emptyList()) {
                            val pid = ProperCodec.pathToId(pathStr)
                            pairsMap.putIfAbsent(pid, pathStr)
                        }
                    }
                    val rootsList = pairsMap.entries.map { it.key to it.value }
                    if (rootsList.isNotEmpty()) {
                        telemetry.agentStatus("indexing", "Rejestrowanie ${rootsList.size} ścieżek w panelu…")
                        uploader.registerPaths(global.agent.server_id, rootsList)
                        uploader.postBackupRoots(machineId, rootsList)
                    }
                }
                for (svc in services) {
                    println("--- Service: ${svc.service}")
                    runService(svc, global, stateDir, password, uploader, dryRun, global.agent.server_id, telemetry)
                }
                println("[agent] Notifying buffer: scan-complete")
                uploader.notifyScanComplete(global.agent.server_id)
            }
        } finally {
            val pauseSec = maxOf(5, global.agent.poll_interval_seconds)
            telemetry.agentStatus("idle", "Skan zakończony · następny za ${pauseSec}s")
        }
    }

    private fun runAgentLoop(globalYml: Path, confDir: Path, dryRun: Boolean) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                runOneBackupCycle(globalYml, confDir, dryRun)
            } catch (e: Exception) {
                System.err.println("[agent] cykl: ${e.message ?: e}")
                runCatching {
                    val g = ConfigLoader.loadGlobal(globalYml)
                    val machineId = MachineIdentity.getOrCreate(MachineIdentity.defaultDeviceIdFile(globalYml))
                    RemoteTelemetry(g, machineId).log("Agent", -500, "Cycle error: ${e.message ?: e}", "ERROR")
                }
            }
            // Continuous scanning: short pause (5s default) between cycles instead of 60s idle.
            // Configurable via PROPERBACKUP_POLL_INTERVAL; minimum is 5s.
            var pauseSec = 5
            var metricsUploader: BufferUploader? = null
            var metricsServerId = ""
            try {
                val g = ConfigLoader.loadGlobal(globalYml)
                pauseSec = maxOf(5, g.agent.poll_interval_seconds)
                if (g.agent.server_id.isNotBlank()) {
                    val tok = resolveEnv(g.agent.upload_token)
                    metricsUploader = BufferUploader(g.agent.buffer_url, tok)
                    metricsServerId = g.agent.server_id
                }
            } catch (ignored: Exception) {
            }
            // Recovery polling: check for pending recovery commands from buffer
            var recoveryPoller: RecoveryPoller? = null
            try {
                val g = ConfigLoader.loadGlobal(globalYml)
                if (g.agent.server_id.isNotBlank()) {
                    val tok = resolveEnv(g.agent.upload_token)
                    val stateDir = Path.of(g.agent.state_dir)
                    val roots = ConfigLoader.loadServices(confDir)
                        .flatMap { it.paths ?: emptyList() }
                    val executor = RecoveryExecutor(g.agent.buffer_url, tok, g.agent.server_id, stateDir, roots)
                    recoveryPoller = RecoveryPoller(g.agent.buffer_url, tok, g.agent.server_id, executor)
                }
            } catch (ignored: Exception) {
            }

            val heartbeatIntervalMs = 10_000L
            var elapsed = 0L
            val totalMs = pauseSec * 1000L
            try {
                while (elapsed < totalMs && !Thread.currentThread().isInterrupted) {
                    val sleepChunk = minOf(heartbeatIntervalMs, totalMs - elapsed)
                    Thread.sleep(sleepChunk)
                    elapsed += sleepChunk
                    runCatching {
                        val metrics = collectMetrics()
                        metricsUploader?.reportMetrics(
                            metricsServerId,
                            metrics.cpuPercent, metrics.ramUsedMb, metrics.ramTotalMb,
                            metrics.perCoreCpu, metrics.coreCount,
                        )
                    }
                    // Poll buffer for recovery commands every heartbeat
                    runCatching { recoveryPoller?.poll() }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } finally {
                runCatching { metricsUploader?.close() }
            }
        }
    }

    @Throws(Exception::class)
    private fun runService(
        svc: ServiceConfig,
        global: GlobalConfig,
        stateDir: Path,
        password: String,
        uploader: BufferUploader,
        dryRun: Boolean,
        serverId: String,
        telemetry: RemoteTelemetry,
    ) {
        val allExcludes = ArrayList(global.default_excludes)
        allExcludes.addAll(svc.exclude)

        val scanner = DifferentialScanner(allExcludes)
        val cache = MetadataCache(stateDir, svc.service!!)
        val prevState = cache.load()

        var totalFilesDiscovered = 0
        var filesProcessed = 0

        for (pathStr in svc.paths!!) {
            val root = Path.of(pathStr)
            println("  Scanning: $root")
            telemetry.agentStatus("indexing", "Skan dysku: $pathStr", currentFile = pathStr)
            telemetry.log("Agent", -1000, "Scanning path.", meta = mapOf("service" to svc.service, "path" to pathStr))

            val result = try {
                scanner.scan(root, prevState)
            } catch (e: IOException) {
                System.err.println("  ABORT: ${e.message}")
                telemetry.log("Agent", -1001, "Scan failed: ${e.message}", "ERROR", mapOf("path" to pathStr))
                throw e
            }

            totalFilesDiscovered += result.currentState.size
            telemetry.agentStatus(
                "scanning", "Znaleziono ${result.currentState.size} plików w $pathStr",
                totalFiles = totalFilesDiscovered, scannedFiles = filesProcessed,
            )

            println("  Changed: ${result.changedFiles.size} files, Deleted: ${result.deletedRelativePaths.size} paths")

            if (dryRun) {
                result.changedFiles.forEach { f -> println("  [DRY] changed: $f") }
                result.deletedRelativePaths.forEach { d -> println("  [DRY] deleted: $d") }
                filesProcessed += result.currentState.size
                continue
            }

            val pathId = ProperCodec.pathToId(pathStr)

            if (result.changedFiles.isNotEmpty()) {
                telemetry.agentStatus(
                    "uploading", "Pakowanie kopii: $pathStr (${result.changedFiles.size} plików)",
                    totalFiles = totalFilesDiscovered, scannedFiles = filesProcessed,
                )
                result.changedFiles.forEachIndexed { idx, f ->
                    telemetry.fileState(root.resolve(f).toString(), "changed")
                    if (idx % 50 == 0) {
                        telemetry.agentStatus(
                            "uploading", "Przesyłanie: ${root.resolve(f).fileName}",
                            totalFiles = totalFilesDiscovered,
                            scannedFiles = filesProcessed + idx,
                            currentFile = root.resolve(f).toString(),
                        )
                    }
                }
                val idxSnap = uploader.fetchPathsIdxForEmbed(serverId)
                if (idxSnap.isEmpty()) {
                    println("  (paths.idx snapshot not embedded — buffer unreachable or empty server_id)")
                }
                val tarGz = TarGzPacker.packToBytes(
                    root,
                    result.changedFiles,
                    if (idxSnap.isNotEmpty()) idxSnap else null,
                )
                val encrypted = encrypt(password, tarGz)
                uploader.upload(encrypted, pathId, pathStr, "A", serverId)
                println("  Uploaded ${encrypted.size} bytes (encrypted) for $pathStr")
                result.changedFiles.forEach { f ->
                    telemetry.fileState(root.resolve(f).toString(), "current")
                }
                filesProcessed += result.changedFiles.size
            }

            if (result.deletedRelativePaths.isNotEmpty()) {
                uploader.upload(ByteArray(0), pathId, pathStr, "D", serverId)
                println("  Uploaded tombstone (D) for $pathStr")
                result.deletedRelativePaths.forEach { d ->
                    telemetry.fileState(root.resolve(d).toString(), "deleted")
                }
            }

            cache.save(result.currentState)

            // Panel „Pliki na żywo”: po każdym skanie wyślij pełny zestaw znanych plików (nie tylko deltę).
            // Bez tego kolejne cykle bez zmian nie wysyłają nic — tabela w panelu zostaje pusta.
            if (global.agent.server_id.isNotBlank()) {
                for ((rel, _) in result.currentState) {
                    telemetry.fileState(root.resolve(rel).toString(), "current")
                }
            }
            filesProcessed += result.currentState.size - result.changedFiles.size
            telemetry.agentStatus(
                "indexing",
                "Zaktualizowano indeks: $pathStr · ${result.currentState.size} pozycji",
                totalFiles = totalFilesDiscovered, scannedFiles = filesProcessed,
            )
        }
    }

    @Throws(Exception::class)
    private fun encrypt(password: String, plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ProperCrypto.encrypt(password.toCharArray(), ByteArrayInputStream(plain), out)
        return out.toByteArray()
    }

    private data class Metrics(
        val cpuPercent: Float,
        val ramUsedMb: Float,
        val ramTotalMb: Float,
        val perCoreCpu: List<Float>,
        val coreCount: Int,
    )

    private fun collectMetrics(): Metrics {
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        val coreCount = Runtime.getRuntime().availableProcessors()
        val cpu: Float
        val perCore = ArrayList<Float>(coreCount)

        if (osBean is com.sun.management.OperatingSystemMXBean) {
            // System-wide CPU (all cores averaged)
            cpu = (osBean.systemCpuLoad * 100).toFloat().coerceIn(0f, 100f)
            // Per-core: use /proc/stat on Linux, fallback to even distribution
            val perCoreFromProc = readProcStatPerCore(coreCount)
            if (perCoreFromProc != null) {
                perCore.addAll(perCoreFromProc)
            } else {
                repeat(coreCount) { perCore.add(cpu) }
            }
        } else {
            cpu = (osBean.systemLoadAverage.toFloat() / coreCount * 100).coerceIn(0f, 100f)
            repeat(coreCount) { perCore.add(cpu) }
        }

        val rt = Runtime.getRuntime()
        val totalMb = rt.maxMemory() / (1024f * 1024f)
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024f)
        return Metrics(cpu, usedMb, totalMb, perCore, coreCount)
    }

    /**
     * Read per-core CPU usage from /proc/stat (Linux only).
     * Returns null on non-Linux systems or if reading fails.
     */
    @Volatile private var prevCoreTimes: LongArray? = null
    @Volatile private var prevCoreIdle: LongArray? = null

    private fun readProcStatPerCore(coreCount: Int): List<Float>? {
        try {
            val procStat = java.io.File("/proc/stat")
            if (!procStat.exists()) return null

            val lines = procStat.readLines()
            val coreTotals = LongArray(coreCount)
            val coreIdles = LongArray(coreCount)
            var found = 0

            for (line in lines) {
                if (!line.startsWith("cpu") || line.startsWith("cpu ")) continue
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 5) continue
                val coreIdx = parts[0].removePrefix("cpu").toIntOrNull() ?: continue
                if (coreIdx >= coreCount) continue
                val vals = parts.drop(1).mapNotNull { it.toLongOrNull() }
                if (vals.size < 4) continue
                val idle = vals[3] + (if (vals.size > 4) vals[4] else 0L)
                val total = vals.sum()
                coreTotals[coreIdx] = total
                coreIdles[coreIdx] = idle
                found++
            }

            if (found < coreCount) return null

            val prev = prevCoreTimes
            val prevIdle = prevCoreIdle
            prevCoreTimes = coreTotals.clone()
            prevCoreIdle = coreIdles.clone()

            if (prev == null || prevIdle == null || prev.size != coreCount) {
                return null
            }

            val result = ArrayList<Float>(coreCount)
            for (i in 0 until coreCount) {
                val dTotal = coreTotals[i] - prev[i]
                val dIdle = coreIdles[i] - prevIdle[i]
                val usage = if (dTotal > 0) ((dTotal - dIdle).toFloat() / dTotal * 100f).coerceIn(0f, 100f) else 0f
                result.add(usage)
            }
            return result
        } catch (_: Exception) {
            return null
        }
    }

    // ── Service installation ──

    private fun handleInstallService(globalYml: Path, confDir: Path) {
        val agentBinary = resolveAgentBinary()
        if (agentBinary == null) {
            System.err.println("Cannot determine agent binary path.")
            if (!ServiceInstaller.hasSudo()) {
                println("\nTo install as a service, run:")
                println("  sudo properbackup install-service")
            }
            exitProcess(1)
        }
        val result = ServiceInstaller.install(agentBinary, confDir)
        println(result.message)
        if (result.unitPath != null) {
            println("Service file: ${result.unitPath}")
        }
        if (!result.success && result.manualCommand != null) {
            println("\nManual installation command:")
            println(result.manualCommand)
        }
        exitProcess(if (result.success) 0 else 1)
    }

    private fun resolveAgentBinary(): Path? {
        // Prefer the shell launcher from jlinkDist distribution
        val cwd = Path.of(System.getProperty("user.dir"))
        val distLauncher = cwd.resolve("properbackup-agent")
        if (Files.exists(distLauncher) && Files.isExecutable(distLauncher)) return distLauncher
        // Fall back to the JAR itself
        val jar = selfJarPath()
        if (jar.isPresent) return jar.get()
        return null
    }

    // ── First-run access code activation ──

    @JvmStatic
    fun runAccessCodeActivation(globalYml: Path, confDir: Path) {
        val global = ConfigLoader.loadGlobal(globalYml)
        val bufferUrl = global.agent.buffer_url.trimEnd('/')
        if (bufferUrl.isBlank()) {
            System.err.println("[activation] No buffer_url configured in $globalYml")
            exitProcess(2)
        }

        val machineId = MachineIdentity.getOrCreate(MachineIdentity.defaultDeviceIdFile(globalYml))
        val accessCode = AccessCodeGenerator.generate()
        val formatted = AccessCodeGenerator.formatForDisplay(accessCode)

        println()
        println("╔══════════════════════════════════════════════╗")
        println("║        ProperBackup — First Run Setup        ║")
        println("╠══════════════════════════════════════════════╣")
        println("║                                              ║")
        println("║   Your access code:  $formatted         ║")
        println("║                                              ║")
        println("║   Enter this code in the ProperBackup web    ║")
        println("║   panel to link this server.                 ║")
        println("║                                              ║")
        println("╚══════════════════════════════════════════════╝")
        println()

        val client = ActivationClient(bufferUrl)
        try {
            client.registerAccessCode(accessCode, machineId)
        } catch (e: Exception) {
            System.err.println("[activation] Could not register code with buffer: ${e.message}")
            println("You can also activate manually: properbackup-agent --activate <TOKEN>")
            return
        }

        println("Waiting for activation via web panel...")
        println("(Press Ctrl+C to cancel)")
        println()

        var attempt = 0
        while (true) {
            attempt++
            try {
                Thread.sleep(5_000)
                val result = client.pollAccessCode(accessCode, machineId)
                if (result != null) {
                    GlobalConfigWriter.setServerId(globalYml, result.serverId)
                    println("[activation] Server activated: ${result.name} (${result.serverId})")

                    if (ServiceInstaller.hasSudo()) {
                        println("[activation] Installing systemd service...")
                        val agentBin = resolveAgentBinary()
                        if (agentBin != null) {
                            val svcResult = ServiceInstaller.install(agentBin, confDir)
                            println(svcResult.message)
                        }
                    } else {
                        println()
                        println("To start ProperBackup as a service:")
                        println("  sudo properbackup install-service")
                    }
                    return
                }
                if (attempt % 12 == 0) {
                    println("Still waiting... code: $formatted")
                }
            } catch (e: InterruptedException) {
                println("\n[activation] Cancelled.")
                return
            } catch (e: Exception) {
                if (attempt % 6 == 0) {
                    System.err.println("[activation] Poll error: ${e.message}")
                }
            }
        }
    }

    private fun agentVersion(): String {
        val props = AgentMain::class.java.getResourceAsStream("/agent-version.properties")
        if (props != null) {
            val p = java.util.Properties()
            p.load(props)
            return p.getProperty("version", "dev")
        }
        return "dev"
    }
}
