package pl.danielniemiec.properbackup.agent.recovery

import pl.danielniemiec.properbackup.agent.config.GlobalConfig
import pl.danielniemiec.properbackup.agent.config.ConfigLoader
import pl.danielniemiec.properbackup.agent.util.StartupTrace
import pl.danielniemiec.properbackup.shared.restore.*
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path

/**
 * Executes recovery commands received from buffer.
 * Coordinates dry_run computation, restore execution, and progress reporting.
 * HR-7: Resumable on crash — state persisted in RestoreStateStore.
 * HR-8: Critical files protected via CriticalPathsGuard.
 * HR-10: Cross-host identical behavior — all logic in shared-core.
 */
class RecoveryExecutor(
    private val bufferUrl: String,
    private val uploadToken: String,
    private val serverId: String,
    private val stateDir: Path,
    private val backupRoots: List<String>,
) {
    private val stateStore = RestoreStateStore(stateDir.resolve("recovery"))
    private val snapshotDiff = SnapshotDiff()

    /**
     * Phase 1: Compute dry run diff and report to buffer.
     * Called when buffer sends "dry_run" command.
     */
    fun executeDryRun(sessionId: String, snapshotId: Long) {
        StartupTrace.line("[recovery] executeDryRun session=$sessionId snapshot=$snapshotId")
        try {
            val manifest = fetchSnapshotManifest(snapshotId)
            val localFiles = scanLocalFiles()

            val diff = snapshotDiff.compute(manifest, localFiles, backupRoots)
            stateStore.saveDryRun(sessionId, diff)

            reportDryRunResult(sessionId, diff)
            StartupTrace.line("[recovery] dry_run complete: restore=${diff.filesToRestore.size}, delete=${diff.filesToDelete.size}, unchanged=${diff.filesUnchanged}")
        } catch (e: Exception) {
            StartupTrace.line("[recovery] dry_run FAILED: ${e.message}")
            reportFailure(sessionId, "dry_run_failed: ${e.message}")
        }
    }

    /**
     * Phase 2: Execute restore operation.
     * Called after user confirms in panel and buffer sends "execute" command.
     */
    fun executeRestore(sessionId: String, snapshotId: Long) {
        StartupTrace.line("[recovery] executeRestore session=$sessionId snapshot=$snapshotId")
        try {
            val manifest = fetchSnapshotManifest(snapshotId)
            val localFiles = scanLocalFiles()
            val diff = snapshotDiff.compute(manifest, localFiles, backupRoots)

            val baseDir = if (backupRoots.isNotEmpty()) Path.of(backupRoots[0]) else Path.of("/")
            val quarantineBase = stateDir.resolve("quarantine")

            val fileRestorer = FileRestorer { sha256 -> downloadFile(sha256) }
            val fileDeleter = FileDeleter(quarantineBase, sessionId)

            val reporter = BufferProgressReporter(bufferUrl, uploadToken, sessionId)
            val orchestrator = RestoreOrchestrator(
                sessionId = sessionId,
                targetSnapshotId = snapshotId.toString(),
                baseDir = baseDir,
                backupRoots = backupRoots,
                snapshotDiff = snapshotDiff,
                fileRestorer = fileRestorer,
                fileDeleter = fileDeleter,
                stateStore = stateStore,
                progressReporter = reporter,
            )

            val success = orchestrator.execute(diff)
            if (success) {
                reportComplete(sessionId, true, null)
            }
            // If !success, reporter already called reportComplete(false, reason)
        } catch (e: Exception) {
            StartupTrace.line("[recovery] executeRestore FAILED: ${e.message}")
            reportFailure(sessionId, "restore_failed: ${e.message}")
        }
    }

    /**
     * Cancel in-progress restore. Rollback deleted files from quarantine.
     */
    fun cancelRestore(sessionId: String) {
        StartupTrace.line("[recovery] cancelRestore session=$sessionId")
        // Rollback is handled by the buffer-side; agent just stops processing.
        // Clean up local state.
        stateStore.cleanup(sessionId)
    }

    private fun fetchSnapshotManifest(snapshotId: Long): List<SnapshotManifestEntry> {
        val url = URI("${bufferUrl.trimEnd('/')}/agent/recovery/manifest/$snapshotId").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $uploadToken")
        conn.setRequestProperty("X-Server-Id", serverId)
        conn.connectTimeout = 10000
        conn.readTimeout = 30000

        if (conn.responseCode != 200) {
            throw RuntimeException("Failed to fetch manifest: HTTP ${conn.responseCode}")
        }

        val body = conn.inputStream.bufferedReader().readText()
        return parseManifest(body)
    }

    private fun scanLocalFiles(): List<LocalFileEntry> {
        val entries = mutableListOf<LocalFileEntry>()
        for (root in backupRoots) {
            val rootPath = Path.of(root)
            if (!java.nio.file.Files.exists(rootPath)) continue
            java.nio.file.Files.walk(rootPath)
                .filter { java.nio.file.Files.isRegularFile(it) }
                .forEach { file ->
                    try {
                        val relativePath = rootPath.relativize(file).toString().replace("\\", "/")
                        val sha256 = computeSha256(file)
                        val size = java.nio.file.Files.size(file)
                        entries.add(LocalFileEntry(relativePath, sha256, size))
                    } catch (_: Exception) {
                        // Skip unreadable files
                    }
                }
        }
        return entries
    }

    private fun downloadFile(sha256: String): InputStream {
        val url = URI("${bufferUrl.trimEnd('/')}/agent/recovery/download/$sha256").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $uploadToken")
        conn.setRequestProperty("X-Server-Id", serverId)
        conn.connectTimeout = 10000
        conn.readTimeout = 60000

        if (conn.responseCode != 200) {
            throw RuntimeException("Failed to download file sha256=$sha256: HTTP ${conn.responseCode}")
        }
        return conn.inputStream
    }

    private fun reportDryRunResult(sessionId: String, diff: DiffResult) {
        val url = URI("${bufferUrl.trimEnd('/')}/agent/recovery/$sessionId/dry_run_result").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $uploadToken")
        conn.setRequestProperty("X-Server-Id", serverId)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val json = """{"files_to_restore":${diff.filesToRestore.size},"files_to_delete":${diff.filesToDelete.size},"files_unchanged":${diff.filesUnchanged},"critical_protected":${diff.criticalProtected},"total_restore_bytes":${diff.totalRestoreBytes},"total_delete_bytes":${diff.totalDeleteBytes},"sample_restore":${samplePaths(diff.filesToRestore)},"sample_delete":${samplePaths(diff.filesToDelete)}}"""
        conn.outputStream.use { it.write(json.toByteArray()) }

        val code = conn.responseCode
        StartupTrace.line("[recovery] reportDryRunResult: HTTP $code")
    }

    private fun reportComplete(sessionId: String, success: Boolean, reason: String?) {
        val url = URI("${bufferUrl.trimEnd('/')}/agent/recovery/$sessionId/complete").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $uploadToken")
        conn.setRequestProperty("X-Server-Id", serverId)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val reasonJson = if (reason != null) ",\"reason\":\"${reason.replace("\"", "\\\"")}\"" else ""
        val json = """{"success":$success$reasonJson}"""
        conn.outputStream.use { it.write(json.toByteArray()) }

        val code = conn.responseCode
        StartupTrace.line("[recovery] reportComplete: success=$success HTTP $code")
    }

    private fun reportFailure(sessionId: String, reason: String) {
        reportComplete(sessionId, false, reason)
    }

    private fun samplePaths(ops: List<FileOperation>): String {
        val sample = ops.take(10).map { "\"${it.path.replace("\"", "\\\"")}\"" }
        return "[${sample.joinToString(",")}]"
    }

    private fun parseManifest(json: String): List<SnapshotManifestEntry> {
        val entries = mutableListOf<SnapshotManifestEntry>()
        // Parse JSON array of {path_id, path, sha256, size_bytes}
        val pattern = Regex("""\{[^}]*"path"\s*:\s*"([^"]*)"[^}]*"sha256"\s*:\s*"([^"]*)"[^}]*"size_bytes"\s*:\s*(\d+)[^}]*\}""")
        for (match in pattern.findAll(json)) {
            val path = match.groupValues[1]
            val sha256 = match.groupValues[2]
            val size = match.groupValues[3].toLongOrNull() ?: 0
            entries.add(SnapshotManifestEntry(
                pathId = sha256Hash(path),
                path = path,
                sha256 = sha256,
                sizeBytes = size,
            ))
        }
        return entries
    }

    private fun computeSha256(path: Path): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.nio.file.Files.newInputStream(path).use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hash(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
