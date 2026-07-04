package pl.danielniemiec.properbackup.agent.host

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * VPS/Dedicated host adapter. Declares capabilities for standalone Linux/macOS/Windows agent.
 * Thin wrapper — all domain logic lives in properbackup-shared.
 */
class VpsHostAdapter(private val installDir: Path) {

    data class Capabilities(
        val maxIoBytesPerSec: Long = 50L * 1024 * 1024,
        val maxCpuPercent: Int = 25,
        val supportsSystemd: Boolean = detectSystemd(),
    )

    fun capabilities(): Capabilities = Capabilities()

    fun detectSudo(): Boolean = try {
        val pb = ProcessBuilder("sudo", "-n", "true")
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    fun installDir(): Path = installDir

    fun confDir(): Path = installDir.resolve("conf.d")

    fun stateDir(): Path {
        val dir = installDir.resolve("state")
        if (!Files.exists(dir)) Files.createDirectories(dir)
        return dir
    }

    companion object {
        fun detectSystemd(): Boolean = try {
            val os = System.getProperty("os.name", "").lowercase()
            if (!os.contains("linux")) {
                false
            } else {
                val pb = ProcessBuilder("systemctl", "--version")
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                pb.start().waitFor() == 0
            }
        } catch (_: Exception) {
            false
        }

        fun collectCpuPercent(): Float {
            val osBean = ManagementFactory.getOperatingSystemMXBean()
            return if (osBean is com.sun.management.OperatingSystemMXBean) {
                @Suppress("DEPRECATION")
                (osBean.systemCpuLoad * 100).toFloat().coerceIn(0f, 100f)
            } else {
                val cores = Runtime.getRuntime().availableProcessors()
                (osBean.systemLoadAverage.toFloat() / cores * 100).coerceIn(0f, 100f)
            }
        }
    }
}
