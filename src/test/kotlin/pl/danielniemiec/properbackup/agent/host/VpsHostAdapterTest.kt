package pl.danielniemiec.properbackup.agent.host

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VpsHostAdapterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `capabilities returns default VPS values`() {
        val adapter = VpsHostAdapter(tempDir)
        val caps = adapter.capabilities()
        assertEquals(50L * 1024 * 1024, caps.maxIoBytesPerSec, "VPS should have 50MB/s IO limit")
        assertEquals(25, caps.maxCpuPercent, "VPS should have 25% CPU limit")
    }

    @Test
    fun `confDir returns conf_d under install dir`() {
        val adapter = VpsHostAdapter(tempDir)
        assertTrue(adapter.confDir().toString().endsWith("conf.d"))
    }

    @Test
    fun `stateDir creates directory`() {
        val adapter = VpsHostAdapter(tempDir)
        val stateDir = adapter.stateDir()
        assertTrue(stateDir.toFile().exists(), "State dir should be created")
    }

    @Test
    fun `collectCpuPercent returns value in range`() {
        val cpu = VpsHostAdapter.collectCpuPercent()
        assertTrue(cpu in 0f..100f, "CPU should be between 0 and 100, got $cpu")
    }
}
