package pl.danielniemiec.properbackup.agent.restore

import com.google.gson.Gson
import pl.danielniemiec.properbackup.core.restore.RestoreFileProvider
import pl.danielniemiec.properbackup.core.restore.RestoreProgressReporter
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * VPS-specific adapter: downloads files from buffer and reports progress.
 * Implements RestoreFileProvider (HR-10 cross-host parity) and RestoreProgressReporter.
 */
class BufferRestoreClient(
    baseBufferUrl: String,
    private val token: String,
    private val serverId: String,
) : RestoreFileProvider, RestoreProgressReporter {

    private val bufferUrl = baseBufferUrl.replace("/+$".toRegex(), "")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val gson = Gson()

    override fun openFileStream(path: String, sha256: String?): InputStream? {
        val objectName = sha256 ?: return null
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/objects/$objectName"))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .GET()
                .build()
            val response = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() == 200) response.body() else null
        } catch (_: Exception) {
            null
        }
    }

    override fun reportProgress(sessionId: String, filesRestoredDelta: Int, filesDeletedDelta: Int, bytesDelta: Long) {
        postJson(
            "$bufferUrl/agent/recovery/$sessionId/progress",
            mapOf(
                "files_restored_delta" to filesRestoredDelta,
                "files_deleted_delta" to filesDeletedDelta,
                "bytes_delta" to bytesDelta,
            ),
        )
    }

    override fun reportComplete(sessionId: String, success: Boolean, failureReason: String?) {
        postJson(
            "$bufferUrl/agent/recovery/$sessionId/complete",
            mapOf("success" to success, "failure_reason" to failureReason),
        )
    }

    override fun reportDryRunResult(
        sessionId: String,
        filesToRestore: Int,
        filesToDelete: Int,
        filesUnchanged: Int,
        totalBytes: Long,
        sampleRestore: List<String>,
        sampleDelete: List<String>,
        criticalProtected: Int,
        estimatedDurationSeconds: Int?,
    ) {
        postJson(
            "$bufferUrl/agent/recovery/$sessionId/dry_run_result",
            mapOf(
                "files_to_restore" to filesToRestore,
                "files_to_delete" to filesToDelete,
                "files_unchanged" to filesUnchanged,
                "total_bytes" to totalBytes,
                "sample_restore" to sampleRestore,
                "sample_delete" to sampleDelete,
                "critical_protected" to criticalProtected,
                "estimated_duration_seconds" to estimatedDurationSeconds,
            ),
        )
    }

    fun pollCommands(): List<Map<String, Any?>> {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/recovery/poll"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .GET()
                .build()
            val response = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val body = gson.fromJson(response.body(), Map::class.java)
                @Suppress("UNCHECKED_CAST")
                (body["commands"] as? List<Map<String, Any?>>) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun postJson(url: String, payload: Map<String, Any?>) {
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build()
            http.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (_: Exception) { }
    }
}
