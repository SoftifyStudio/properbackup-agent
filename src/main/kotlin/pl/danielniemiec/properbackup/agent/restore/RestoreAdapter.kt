package pl.danielniemiec.properbackup.agent.restore

import java.io.File
import java.io.InputStream

class RestoreAdapter(
    private val poller: RecoveryPoller,
    private val storageRoot: File,
    private val stateDbPath: File,
    private val quarantineRoot: File,
    extraProtectedPaths: List<String> = emptyList(),
) {
    private val guard = CriticalPathsGuard(extraProtectedPaths)
    private val fileRestorer = FileRestorer()
    private val fileDeleter = FileDeleter(quarantineRoot, guard)
    private val snapshotDiff = SnapshotDiff()

    private var currentExecutor: RestoreExecutor? = null

    fun checkAndExecute(
        snapshotFiles: Map<String, SnapshotFileInfo>,
        currentFiles: Map<String, CurrentFileInfo>,
    ) {
        val pollResult = poller.poll() ?: return

        when (pollResult.state) {
            "PLANNING" -> handlePlanning(pollResult, snapshotFiles, currentFiles)
            "READY" -> handleRestore(pollResult, snapshotFiles)
        }
    }

    fun cancel() {
        currentExecutor?.cancel()
    }

    private fun handlePlanning(
        poll: RecoveryPollResult,
        snapshotFiles: Map<String, SnapshotFileInfo>,
        currentFiles: Map<String, CurrentFileInfo>,
    ) {
        val diff = snapshotDiff.compute(snapshotFiles, currentFiles)
        val (_, blockedDeletions) = guard.filterDeletions(diff.sampleDelete)
        val estimatedSeconds = estimateDuration(diff)

        poller.reportDryRunResult(
            sessionId = poll.sessionId,
            filesToRestore = diff.filesToRestore,
            filesToRestoreSize = diff.totalRestoreBytes,
            filesToDelete = diff.filesToDelete,
            filesToDeleteSize = 0,
            filesUnchanged = diff.filesUnchanged,
            criticalFilesProtected = blockedDeletions.size,
            sampleRestore = diff.sampleRestore,
            sampleDelete = diff.sampleDelete,
            estimatedDurationSeconds = estimatedSeconds,
        )
    }

    private fun handleRestore(
        poll: RecoveryPollResult,
        snapshotFiles: Map<String, SnapshotFileInfo>,
    ) {
        val stateStore = RestoreStateStore(stateDbPath)
        try {
            val localFileSource = object : RestoreFileSource {
                override fun openFile(path: String): InputStream {
                    return File(storageRoot, path).inputStream()
                }
            }

            val executor = RestoreExecutor(
                stateStore = stateStore,
                fileRestorer = fileRestorer,
                fileDeleter = fileDeleter,
                guard = guard,
                fileSource = localFileSource,
            )
            currentExecutor = executor

            val currentFiles = scanCurrentFiles()
            val diff = snapshotDiff.compute(snapshotFiles, currentFiles)

            val callback = object : RestoreProgressCallback {
                private var lastReportTime = 0L

                override fun onProgress(filesRestored: Int, filesDeleted: Int, bytesTransferred: Long) {
                    val now = System.currentTimeMillis()
                    if (now - lastReportTime > 2000) {
                        poller.reportProgress(poll.sessionId, filesRestored, filesDeleted, bytesTransferred)
                        lastReportTime = now
                    }
                }

                override fun onOperationDone(op: RestoreOperation, result: String) {
                    // logged via onProgress throttle
                }

                override fun onComplete(filesRestored: Int, filesDeleted: Int, bytesTransferred: Long) {
                    poller.reportComplete(poll.sessionId, filesRestored, filesDeleted, bytesTransferred)
                }

                override fun onFailed(reason: String) {
                    // buffer detects failure via polling timeout or agent crash recovery
                }
            }

            executor.execute(poll.sessionId, diff, callback)
        } finally {
            stateStore.close()
            currentExecutor = null
        }
    }

    private fun scanCurrentFiles(): Map<String, CurrentFileInfo> {
        return emptyMap()
    }

    private fun estimateDuration(diff: DiffResult): Int {
        val mbPerSecond = 100
        val seconds = (diff.totalRestoreBytes / (mbPerSecond * 1024 * 1024.0)).toInt()
        return maxOf(5, seconds + diff.filesToDelete)
    }
}
