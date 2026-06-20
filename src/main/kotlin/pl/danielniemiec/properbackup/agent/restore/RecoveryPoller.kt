package pl.danielniemiec.properbackup.agent.restore

import pl.danielniemiec.properbackup.agent.config.GlobalConfig
import pl.danielniemiec.properbackup.agent.logging.RemoteTelemetry
import java.nio.file.Path

/**
 * Polls the buffer for recovery commands during backup cycles.
 * Integrates with the agent's main loop to check for pending recovery actions.
 */
class RecoveryPoller(
    private val global: GlobalConfig,
    private val serverId: String,
    private val token: String,
    private val telemetry: RemoteTelemetry,
    confDir: Path = Path.of("conf.d"),
) {

    private val handler = RecoveryCommandHandler(global, serverId, token, telemetry, confDir)

    fun pollAndHandle(): Boolean {
        if (serverId.isBlank()) return false
        val client = BufferRestoreClient(global.agent.buffer_url, token, serverId)
        val commands = client.pollCommands()
        if (commands.isEmpty()) return false

        for (cmd in commands) {
            handler.handleCommand(cmd)
        }
        return true
    }
}
