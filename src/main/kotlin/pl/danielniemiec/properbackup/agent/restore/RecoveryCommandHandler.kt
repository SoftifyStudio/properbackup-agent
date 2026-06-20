package pl.danielniemiec.properbackup.agent.restore

import pl.danielniemiec.properbackup.agent.config.ConfigLoader
import pl.danielniemiec.properbackup.agent.config.GlobalConfig
import pl.danielniemiec.properbackup.agent.logging.RemoteTelemetry
import pl.danielniemiec.properbackup.core.restore.CriticalPathsGuard
import pl.danielniemiec.properbackup.core.restore.FileDeleter
import pl.danielniemiec.properbackup.core.restore.FileRestorer
import pl.danielniemiec.properbackup.core.restore.RestoreOrchestrator
import pl.danielniemiec.properbackup.core.restore.RestoreStateStore
import pl.danielniemiec.properbackup.core.restore.SnapshotDiff
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles recovery commands received from buffer via polling.
 * Commands: dry_run, execute, cancel.
 */
class RecoveryCommandHandler(
    private val global: GlobalConfig,
    private val serverId: String,
    private val token: String,
    private val telemetry: RemoteTelemetry,
    private val confDir: Path = Path.of("conf.d"),
) {

    private val activeOrchestrator = AtomicReference<RestoreOrchestrator?>(null)

    fun handleCommand(command: Map<String, Any?>) {
        val cmd = command["command"] as? String ?: return
        val sessionId = command["sessionId"] as? String ?: return

        telemetry.log("Recovery", -500, "Received recovery command: $cmd (session=$sessionId)")

        when (cmd) {
            "dry_run" -> handleDryRun(sessionId)
            "execute" -> handleExecute(sessionId)
            "cancel" -> handleCancel(sessionId)
        }
    }

    private fun handleDryRun(sessionId: String) {
        telemetry.agentStatus("recovery_planning", "Computing DRY RUN diff for recovery $sessionId")
        val client = createClient()
        val stateDir = Path.of(global.agent.state_dir)
        val stateStore = RestoreStateStore(stateDir.resolve("recovery-$sessionId.db"))
        val quarantineDir = stateDir.resolve(".quarantine")
        val fileRestorer = FileRestorer(quarantineDir)
        val fileDeleter = FileDeleter(fileRestorer)

        val orchestrator = RestoreOrchestrator(stateStore, fileRestorer, fileDeleter, client, client)

        val localFiles = scanLocalFiles()
        val snapshotFiles = emptyMap<String, SnapshotDiff.FileEntry>()

        try {
            orchestrator.computeDryRun(sessionId, snapshotFiles, localFiles)
            telemetry.log("Recovery", -501, "DRY RUN computed for session $sessionId")
        } catch (e: Exception) {
            telemetry.log("Recovery", -502, "DRY RUN failed: ${e.message}", "ERROR")
            client.reportComplete(sessionId, false, "DRY RUN failed: ${e.message}")
        } finally {
            stateStore.close()
        }
    }

    private fun handleExecute(sessionId: String) {
        telemetry.agentStatus("recovery_restoring", "Executing recovery restore for session $sessionId")
        val client = createClient()
        val stateDir = Path.of(global.agent.state_dir)
        val stateStore = RestoreStateStore(stateDir.resolve("recovery-$sessionId.db"))
        val quarantineDir = stateDir.resolve(".quarantine")
        val fileRestorer = FileRestorer(quarantineDir)
        val fileDeleter = FileDeleter(fileRestorer)

        val orchestrator = RestoreOrchestrator(stateStore, fileRestorer, fileDeleter, client, client)
        activeOrchestrator.set(orchestrator)

        try {
            val pending = stateStore.pendingOperations(sessionId)
            if (pending.isEmpty()) {
                val localFiles = scanLocalFiles()
                val snapshotFiles = emptyMap<String, SnapshotDiff.FileEntry>()
                val diff = SnapshotDiff.compute(snapshotFiles, localFiles)
                orchestrator.planOperations(sessionId, diff)
            }

            val result = orchestrator.executeRestore(sessionId)

            if (result.cancelledMidway) {
                telemetry.log("Recovery", -503, "Recovery cancelled mid-restore, rolling back")
                orchestrator.rollback(sessionId)
                client.reportComplete(sessionId, false, "Cancelled by user")
            } else if (result.success) {
                telemetry.log("Recovery", -504, "Recovery completed: ${result.filesRestored} restored, ${result.filesDeleted} deleted")
                orchestrator.cleanup(sessionId)
                client.reportComplete(sessionId, true)
            } else {
                val errorSummary = result.errors.take(5).joinToString("; ")
                telemetry.log("Recovery", -505, "Recovery failed: $errorSummary", "ERROR")
                client.reportComplete(sessionId, false, errorSummary)
            }
        } catch (e: Exception) {
            telemetry.log("Recovery", -506, "Recovery execution error: ${e.message}", "ERROR")
            client.reportComplete(sessionId, false, "Execution error: ${e.message}")
        } finally {
            activeOrchestrator.set(null)
            stateStore.close()
        }
    }

    private fun handleCancel(sessionId: String) {
        telemetry.log("Recovery", -507, "Cancel requested for session $sessionId")
        activeOrchestrator.get()?.cancel()
    }

    private fun createClient(): BufferRestoreClient {
        return BufferRestoreClient(global.agent.buffer_url, token, serverId)
    }

    private fun scanLocalFiles(): Map<String, SnapshotDiff.FileEntry> {
        val services = ConfigLoader.loadServices(confDir)
        val result = HashMap<String, SnapshotDiff.FileEntry>()

        for (svc in services) {
            val paths = svc.paths ?: continue
            for (pathStr in paths) {
                val rootDir = Path.of(pathStr)
                if (!Files.exists(rootDir)) {
                    telemetry.log("Recovery", -510, "Scan skipping non-existent path: $pathStr")
                    continue
                }
                try {
                    Files.walkFileTree(rootDir, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                            val absPath = file.toAbsolutePath().normalize().toString()
                            if (CriticalPathsGuard.isCritical(absPath)) return FileVisitResult.CONTINUE
                            if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                            try {
                                val bytes = Files.readAllBytes(file)
                                val digest = MessageDigest.getInstance("SHA-256")
                                val sha256 = digest.digest(bytes).joinToString("") { "%02x".format(it) }
                                result[absPath] = SnapshotDiff.FileEntry(absPath, sha256, attrs.size())
                            } catch (e: IOException) {
                                telemetry.log("Recovery", -511, "Scan cannot read $absPath: ${e.message}")
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                            telemetry.log("Recovery", -512, "Scan visit failed: ${file}: ${exc.message}")
                            return FileVisitResult.CONTINUE
                        }
                    })
                } catch (e: IOException) {
                    telemetry.log("Recovery", -513, "Scan error for path $pathStr: ${e.message}", "ERROR")
                }
            }
        }

        telemetry.log("Recovery", -514, "Local filesystem scan complete: ${result.size} files")
        return result
    }
}
