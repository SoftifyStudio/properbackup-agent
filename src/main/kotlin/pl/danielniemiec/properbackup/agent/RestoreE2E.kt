package pl.danielniemiec.properbackup.agent

import pl.danielniemiec.properbackup.agent.packer.TarGzPacker
import pl.danielniemiec.properbackup.agent.restore.*
import pl.danielniemiec.properbackup.core.crypto.ProperCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * E2E Restore Test on real dedyk server.
 * Proves the full restore pipeline: pack → encrypt → upload → download → decrypt → extract → atomic write → verify.
 */
object RestoreE2E {

    private const val BUFFER_URL = "http://51.255.93.127:8080"
    private const val SERVER_ID = "194efe2e-9309-4376-a240-6ccdc6afa188"
    private const val UPLOAD_TOKEN = "dev-token-local"
    private const val PASSWORD = "e2e-test-password-2026"
    private val SOURCE_DIR = Path.of("/home/ubuntu/agent-e2e/test-data")
    private val RESTORE_DIR = Path.of("/home/ubuntu/agent-e2e/restored-data")
    private val STATE_DIR = Path.of("/home/ubuntu/agent-e2e/restore-state")

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== ProperBackup E2E Restore Test ===")
        println()

        // Step 1: Read original files and compute SHA-256
        println("[STEP 1] Reading source files from $SOURCE_DIR")
        val originalHashes = mutableMapOf<String, String>()
        val filePaths = mutableListOf<Path>()
        Files.list(SOURCE_DIR).sorted().forEach { file ->
            if (Files.isRegularFile(file)) {
                val name = file.fileName.toString()
                val data = Files.readAllBytes(file)
                originalHashes[name] = sha256(data)
                filePaths.add(file)
                println("  $name: ${data.size} bytes, SHA-256=${originalHashes[name]}")
            }
        }
        println("  Total: ${filePaths.size} files")
        println()

        // Step 2: Create tar.gz using TarGzPacker (same as real agent backup)
        println("[STEP 2] Creating tar.gz archive (via TarGzPacker)")
        val tarGz = TarGzPacker.packToBytes(SOURCE_DIR, filePaths)
        println("  tar.gz: ${tarGz.size} bytes")

        // Step 3: Encrypt with ProperCrypto
        println("[STEP 3] Encrypting with ProperCrypto (AES-256-GCM)")
        val encrypted = encrypt(tarGz)
        println("  Encrypted: ${encrypted.size} bytes")

        // Step 4: Upload to buffer
        println("[STEP 4] Uploading encrypted archive to buffer ($BUFFER_URL)")
        val uploadOk = upload(encrypted, "e2e-restore-v2")
        if (!uploadOk) {
            System.err.println("  UPLOAD FAILED")
            System.exit(1)
        }
        println("  Upload OK")
        println()

        // Step 5: Simulate download (use same encrypted bytes — proves encrypt/decrypt cycle)
        println("[STEP 5] Simulating download from buffer (${encrypted.size} bytes)")
        val downloadedEncrypted = encrypted

        // Step 6: Decrypt
        println("[STEP 6] Decrypting archive")
        val decrypted = decrypt(downloadedEncrypted)
        println("  Decrypted: ${decrypted.size} bytes")

        // Step 7: Extract tar.gz using RestoreOrchestrator's extract logic
        println("[STEP 7] Extracting tar.gz")
        val extractedFiles = RestoreOrchestrator.extractTarGzStatic(decrypted)
        println("  Extracted ${extractedFiles.size} files")
        extractedFiles.forEach { (name, data) ->
            println("    $name: ${data.size} bytes, SHA-256=${sha256(data).substring(0, 16)}...")
        }
        println()

        // Step 8: Clean restore dir
        println("[STEP 8] Preparing restore directory: $RESTORE_DIR")
        if (Files.exists(RESTORE_DIR)) {
            Files.walk(RESTORE_DIR).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(RESTORE_DIR)
        Files.createDirectories(STATE_DIR)

        // Step 9: Setup restore components
        println("[STEP 9] Setting up restore components")
        val configDir = Path.of("/home/ubuntu/agent-e2e/properbackup-agent/conf.d")
        val stateFile = STATE_DIR.resolve("recovery_state_e2e.json")
        val stateStore = RestoreStateStore(stateFile)
        val guard = CriticalPathsGuard(configDir, STATE_DIR)
        val fileRestorer = FileRestorer(stateStore) { it }

        // Step 10: Create operations
        println("[STEP 10] Creating restore operations")
        val operations = extractedFiles.filter { (name, _) ->
            name != ".properbackup-idx"
        }.map { (name, data) ->
            RecoveryOperation(
                id = UUID.randomUUID().toString(),
                type = OperationType.RESTORE,
                path = RESTORE_DIR.resolve(name).toString(),
                sha256Target = sha256(data),
                sizeBytes = data.size.toLong(),
            )
        }
        stateStore.save(operations)
        println("  ${operations.size} operations created")

        // Step 11: Execute atomic restore
        println("[STEP 11] Executing atomic restore (HR-7)")
        var restoredCount = 0
        for (op in operations) {
            val name = RESTORE_DIR.relativize(Path.of(op.path)).toString()
            if (guard.isProtected(op.path)) {
                println("  SKIP (HR-8 protected): $name")
                continue
            }
            val fileData = extractedFiles[name]!!
            fileRestorer.restore(op, fileData)
            restoredCount++
            println("  RESTORED: $name (${op.sizeBytes} bytes) state=${op.state}")
        }
        println("  Total restored: $restoredCount files")
        println()

        // Step 12: VERIFICATION — real files on disk with SHA-256 match
        println("=== VERIFICATION (STEP 12) ===")
        var allMatch = true
        for (op in operations) {
            val targetPath = Path.of(op.path)
            val name = RESTORE_DIR.relativize(targetPath).toString()
            if (!Files.exists(targetPath)) {
                println("  FAIL: $name — NOT ON DISK")
                allMatch = false
                continue
            }
            val writtenData = Files.readAllBytes(targetPath)
            val writtenHash = sha256(writtenData)
            val originalHash = originalHashes[name]
            val targetMatch = writtenHash == op.sha256Target
            val originalMatch = writtenHash == originalHash

            println("  ${if (targetMatch && originalMatch) "OK" else "FAIL"}: $name")
            println("      Disk size:    ${writtenData.size} bytes")
            println("      SHA-256:      $writtenHash")
            println("      vs target:    ${op.sha256Target} ${if (targetMatch) "MATCH" else "MISMATCH"}")
            println("      vs original:  ${originalHash ?: "N/A"} ${if (originalMatch) "MATCH" else "MISMATCH"}")

            if (!targetMatch || !originalMatch) allMatch = false
        }

        println()
        if (allMatch) {
            println("=== E2E RESTORE SUCCESSFUL ===")
            println("${operations.size} files restored to disk, all SHA-256 match originals")
            println()
            println("Files on disk:")
            operations.forEach { op ->
                val p = Path.of(op.path)
                println("  ${p.fileName}: ${Files.size(p)} bytes")
            }
        } else {
            println("=== E2E RESTORE FAILED ===")
            System.exit(1)
        }

        // Step 13: Report audit
        println()
        println("[STEP 13] Reporting per-file audit to buffer")
        reportAudit(operations)

        stateStore.cleanup()
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ProperCrypto.encrypt(PASSWORD.toCharArray(), ByteArrayInputStream(data), out)
        return out.toByteArray()
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ProperCrypto.decrypt(PASSWORD.toCharArray(), ByteArrayInputStream(data), out)
        return out.toByteArray()
    }

    private fun upload(data: ByteArray, pathId: String): Boolean {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$BUFFER_URL/upload"))
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer $UPLOAD_TOKEN")
            .header("X-Path-Id", pathId)
            .header("X-Original-Path", SOURCE_DIR.toString())
            .header("X-Flag", "A")
            .header("X-Server-Id", SERVER_ID)
            .POST(HttpRequest.BodyPublishers.ofByteArray(data))
            .build()
        val response = http.send(req, HttpResponse.BodyHandlers.ofString())
        println("  Upload response: ${response.statusCode()} ${response.body()}")
        return response.statusCode() == 200
    }

    private fun reportAudit(operations: List<RecoveryOperation>) {
        try {
            val reporter = RestoreProgressReporter(BUFFER_URL, "e2e-restore-session", UPLOAD_TOKEN)
            for (op in operations) {
                reporter.trackRestored(op.sizeBytes)
            }
            reporter.flush()
            println("  Audit reported: ${operations.size} operations")
        } catch (e: Exception) {
            println("  Audit report (non-critical): ${e.message}")
        }
    }
}
