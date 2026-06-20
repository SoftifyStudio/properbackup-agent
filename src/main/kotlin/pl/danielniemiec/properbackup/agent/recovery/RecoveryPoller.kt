package pl.danielniemiec.properbackup.agent.recovery

import pl.danielniemiec.properbackup.agent.transport.BufferUploader
import pl.danielniemiec.properbackup.agent.util.StartupTrace
import java.net.HttpURLConnection
import java.net.URI

/**
 * Polls buffer for pending recovery commands.
 * Buffer endpoint: GET /agent/recovery/poll
 * Response: {"commands":[{"type":"dry_run","session_id":"...","snapshot_id":...}]}
 *
 * On command received, delegates to RecoveryExecutor.
 */
class RecoveryPoller(
    private val bufferUrl: String,
    private val uploadToken: String,
    private val serverId: String,
    private val executor: RecoveryExecutor,
) {
    companion object {
        private const val POLL_PATH = "/agent/recovery/poll"
    }

    /**
     * Poll buffer for pending recovery commands.
     * Called during agent idle time (between backup cycles).
     * Non-blocking: returns immediately if no commands pending.
     */
    fun poll() {
        try {
            val url = URI("${bufferUrl.trimEnd('/')}$POLL_PATH").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $uploadToken")
            conn.setRequestProperty("X-Server-Id", serverId)
            conn.connectTimeout = 5000
            conn.readTimeout = 10000

            val code = conn.responseCode
            if (code != 200) {
                StartupTrace.line("[recovery-poll] HTTP $code from buffer")
                return
            }

            val body = conn.inputStream.bufferedReader().readText()
            val commands = parseCommands(body)

            for (cmd in commands) {
                StartupTrace.line("[recovery-poll] Received command: type=${cmd.type}, session=${cmd.sessionId}")
                when (cmd.type) {
                    "dry_run" -> executor.executeDryRun(cmd.sessionId, cmd.snapshotId)
                    "execute" -> executor.executeRestore(cmd.sessionId, cmd.snapshotId)
                    "cancel" -> executor.cancelRestore(cmd.sessionId)
                    else -> StartupTrace.line("[recovery-poll] Unknown command type: ${cmd.type}")
                }
            }
        } catch (e: Exception) {
            StartupTrace.line("[recovery-poll] Error: ${e.message}")
        }
    }

    private fun parseCommands(json: String): List<RecoveryCommand> {
        // Simple JSON parsing without external dependency
        val commands = mutableListOf<RecoveryCommand>()
        val commandsArray = extractJsonArray(json, "commands")
        for (cmdJson in commandsArray) {
            val type = extractJsonString(cmdJson, "type") ?: continue
            val sessionId = extractJsonString(cmdJson, "session_id") ?: continue
            val snapshotId = extractJsonLong(cmdJson, "snapshot_id") ?: 0L
            commands.add(RecoveryCommand(type, sessionId, snapshotId))
        }
        return commands
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1]
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)"
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun extractJsonArray(json: String, key: String): List<String> {
        val pattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)]"
        val match = Regex(pattern).find(json) ?: return emptyList()
        val arrayContent = match.groupValues[1].trim()
        if (arrayContent.isEmpty()) return emptyList()

        // Split by top-level objects
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in arrayContent.indices) {
            when (arrayContent[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects.add(arrayContent.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return objects
    }
}

data class RecoveryCommand(
    val type: String,
    val sessionId: String,
    val snapshotId: Long,
)
