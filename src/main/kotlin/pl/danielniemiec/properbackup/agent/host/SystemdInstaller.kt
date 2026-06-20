package pl.danielniemiec.properbackup.agent.host

import java.nio.file.Files
import java.nio.file.Path

/**
 * Systemd service installer for Linux.
 * Auto-installs if sudo available; otherwise prints manual command.
 */
object SystemdInstaller {

    private const val SERVICE_NAME = "properbackup-agent"
    private val SERVICE_PATH = Path.of("/etc/systemd/system/$SERVICE_NAME.service")

    fun tryInstallOrPrintInstructions(agentBin: Path, confDir: Path): Boolean {
        if (!VpsHostAdapter.detectSystemd()) {
            println("[agent] systemd not detected — skipping service installation.")
            return false
        }

        val unit = buildUnitFile(agentBin.toAbsolutePath().toString(), confDir.toAbsolutePath().toString())

        val hasSudo = try {
            val pb = ProcessBuilder("sudo", "-n", "true")
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            pb.start().waitFor() == 0
        } catch (_: Exception) {
            false
        }

        if (hasSudo) {
            return autoInstall(unit, agentBin)
        }

        println()
        println("=== Systemd Service Installation ===")
        println("Run the following command to install as a systemd service:")
        println()
        println("  sudo ${agentBin.parent.toAbsolutePath()}/install-service.sh")
        println()
        println("Or manually:")
        println("  sudo tee $SERVICE_PATH <<'EOF'")
        println(unit)
        println("EOF")
        println("  sudo systemctl daemon-reload")
        println("  sudo systemctl enable --now $SERVICE_NAME")
        println()
        return false
    }

    fun isInstalled(): Boolean = Files.exists(SERVICE_PATH)

    private fun autoInstall(unit: String, agentBin: Path): Boolean {
        return try {
            val tmpFile = Files.createTempFile("properbackup-service", ".service")
            Files.writeString(tmpFile, unit)

            exec("sudo", "cp", tmpFile.toString(), SERVICE_PATH.toString())
            exec("sudo", "systemctl", "daemon-reload")
            exec("sudo", "systemctl", "enable", SERVICE_NAME)
            exec("sudo", "systemctl", "start", SERVICE_NAME)

            Files.deleteIfExists(tmpFile)
            println("[agent] systemd service '$SERVICE_NAME' installed and started.")
            true
        } catch (e: Exception) {
            System.err.println("[agent] auto-install failed: ${e.message}")
            false
        }
    }

    private fun buildUnitFile(agentBin: String, confDir: String): String = """
[Unit]
Description=ProperBackup Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$agentBin --headless --conf-dir $confDir
Restart=always
RestartSec=5
WorkingDirectory=${Path.of(agentBin).parent}
StandardOutput=journal
StandardError=journal
SyslogIdentifier=$SERVICE_NAME

[Install]
WantedBy=multi-user.target
""".trim()

    private fun exec(vararg cmd: String) {
        val pb = ProcessBuilder(*cmd)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        val p = pb.start()
        val exit = p.waitFor()
        if (exit != 0) throw RuntimeException("Command '${cmd.joinToString(" ")}' failed with exit code $exit")
    }
}
