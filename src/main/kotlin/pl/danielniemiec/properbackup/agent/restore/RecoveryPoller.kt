package pl.danielniemiec.properbackup.agent.restore

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class RecoveryPoller(
    private val bufferUrl: String,
    private val agentToken: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val gson = Gson()

    fun poll(): RecoveryPollResult? {
        val url = "${bufferUrl.trimEnd('/')}/agent/recovery/poll"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $agentToken")
            .GET()
            .build()

        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 204) return null
        if (resp.statusCode() != 200) return null

        val json = gson.fromJson(resp.body(), JsonObject::class.java)
        return RecoveryPollResult(
            sessionId = json.get("id")?.asString ?: return null,
            targetSnapshotId = json.get("targetSnapshotId")?.asString ?: return null,
            serverId = json.get("serverId")?.asString ?: return null,
            state = json.get("state")?.asString ?: return null,
        )
    }

    fun reportDryRunResult(
        sessionId: String,
        filesToRestore: Int,
        filesToRestoreSize: Long,
        filesToDelete: Int,
        filesToDeleteSize: Long,
        filesUnchanged: Int,
        criticalFilesProtected: Int,
        sampleRestore: List<String>,
        sampleDelete: List<String>,
        estimatedDurationSeconds: Int,
    ) {
        val url = "${bufferUrl.trimEnd('/')}/agent/recovery/$sessionId/dry_run_result"
        val body = gson.toJson(mapOf(
            "filesToRestoreCount" to filesToRestore,
            "filesToRestoreSize" to filesToRestoreSize,
            "filesToDeleteCount" to filesToDelete,
            "filesToDeleteSize" to filesToDeleteSize,
            "filesUnchangedCount" to filesUnchanged,
            "criticalFilesProtectedCount" to criticalFilesProtected,
            "sampleFilesToRestore" to sampleRestore,
            "sampleFilesToDelete" to sampleDelete,
            "estimatedDurationSeconds" to estimatedDurationSeconds,
        ))
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $agentToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        httpClient.send(req, HttpResponse.BodyHandlers.discarding())
    }

    fun reportProgress(sessionId: String, filesRestored: Int, filesDeleted: Int, bytesTransferred: Long) {
        val url = "${bufferUrl.trimEnd('/')}/agent/recovery/$sessionId/progress"
        val body = gson.toJson(mapOf(
            "filesRestored" to filesRestored,
            "filesDeleted" to filesDeleted,
            "bytesTransferred" to bytesTransferred,
        ))
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $agentToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        httpClient.send(req, HttpResponse.BodyHandlers.discarding())
    }

    fun reportComplete(sessionId: String, filesRestored: Int, filesDeleted: Int, bytesTransferred: Long) {
        val url = "${bufferUrl.trimEnd('/')}/agent/recovery/$sessionId/complete"
        val body = gson.toJson(mapOf(
            "filesRestored" to filesRestored,
            "filesDeleted" to filesDeleted,
            "bytesTransferred" to bytesTransferred,
        ))
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $agentToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        httpClient.send(req, HttpResponse.BodyHandlers.discarding())
    }
}

data class RecoveryPollResult(
    val sessionId: String,
    val targetSnapshotId: String,
    val serverId: String,
    val state: String,
)
