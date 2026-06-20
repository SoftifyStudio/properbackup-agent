package pl.danielniemiec.properbackup.agent.recovery

import pl.danielniemiec.properbackup.agent.util.StartupTrace
import pl.danielniemiec.properbackup.shared.restore.DiffResult
import pl.danielniemiec.properbackup.shared.restore.RestoreOrchestrator
import java.net.HttpURLConnection
import java.net.URI

/**
 * Reports restore progress to buffer via HTTP.
 * Implements RestoreOrchestrator.ProgressReporter.
 * Batched: reports every BATCH_SIZE (100) operations.
 */
class BufferProgressReporter(
    private val bufferUrl: String,
    private val uploadToken: String,
    private val sessionId: String,
) : RestoreOrchestrator.ProgressReporter {

    override fun reportDryRun(result: DiffResult) {
        // Dry run is reported separately via RecoveryExecutor.reportDryRunResult
    }

    override fun reportProgress(filesRestored: Int, filesDeleted: Int, bytesTransferred: Long) {
        try {
            val url = URI("${bufferUrl.trimEnd('/')}/agent/recovery/$sessionId/progress").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $uploadToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val json = """{"files_restored":$filesRestored,"files_deleted":$filesDeleted,"bytes_transferred":$bytesTransferred}"""
            conn.outputStream.use { it.write(json.toByteArray()) }

            val code = conn.responseCode
            StartupTrace.line("[recovery-progress] HTTP $code: restored=$filesRestored deleted=$filesDeleted bytes=$bytesTransferred")
        } catch (e: Exception) {
            StartupTrace.line("[recovery-progress] Error reporting: ${e.message}")
        }
    }

    override fun reportComplete(success: Boolean, reason: String?) {
        try {
            val url = URI("${bufferUrl.trimEnd('/')}/agent/recovery/$sessionId/complete").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $uploadToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val reasonJson = if (reason != null) ",\"reason\":\"${reason.replace("\"", "\\\"")}\"" else ""
            val json = """{"success":$success$reasonJson}"""
            conn.outputStream.use { it.write(json.toByteArray()) }

            val code = conn.responseCode
            StartupTrace.line("[recovery-complete] HTTP $code: success=$success")
        } catch (e: Exception) {
            StartupTrace.line("[recovery-complete] Error reporting: ${e.message}")
        }
    }
}
