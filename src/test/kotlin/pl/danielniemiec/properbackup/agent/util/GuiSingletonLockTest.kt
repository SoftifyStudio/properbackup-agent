package pl.danielniemiec.properbackup.agent.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GuiSingletonLockTest {

    @TempDir
    lateinit var tempSupport: Path

    @BeforeEach
    fun isolateSupportDir() {
        System.setProperty("properbackup.support.dir.override", tempSupport.toString())
    }

    @AfterEach
    fun clearOverride() {
        System.clearProperty("properbackup.support.dir.override")
    }

    @Test
    fun acquireReleaseThenAcquireAgain() {
        val a = GuiSingletonLock.tryAcquire()
        assertNotNull(a)
        a!!.close()
        val b = GuiSingletonLock.tryAcquire()
        assertNotNull(b)
        b!!.close()
    }

    @Test
    fun twoConcurrentLocksSecondFails() {
        val a = GuiSingletonLock.tryAcquire()
        assertNotNull(a)
        val b = GuiSingletonLock.tryAcquire()
        assertNull(b)
        a!!.close()
    }
}
