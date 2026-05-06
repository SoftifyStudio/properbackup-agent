package pl.danielniemiec.properbackup.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class AgentMainExplorerLaunchTest {

    @Test
    fun explorerStyleEmptyArgs() {
        assertTrue(AgentMain.isExplorerStyleJarLaunch(emptyArray()))
    }

    @Test
    fun explorerStyleMacosPsnOnly() {
        assertTrue(AgentMain.isExplorerStyleJarLaunch(arrayOf("-psn_0_9893378")))
        assertTrue(AgentMain.isExplorerStyleJarLaunch(arrayOf("-psn_0_1", "-psn_0_2")))
    }

    @Test
    fun explorerStyleMacosAwtNoise() {
        assertTrue(AgentMain.isExplorerStyleJarLaunch(arrayOf("-XstartOnFirstThread")))
        assertTrue(AgentMain.isExplorerStyleJarLaunch(arrayOf("-Xdock:name=ProperBackup")))
        assertTrue(
            AgentMain.isExplorerStyleJarLaunch(
                arrayOf("-psn_0_1", "-XstartOnFirstThread", "-Xdock:name=PB"),
            ),
        )
    }

    @Test
    fun notExplorerStyleRealCli() {
        assertFalse(AgentMain.isExplorerStyleJarLaunch(arrayOf("--headless")))
        assertFalse(AgentMain.isExplorerStyleJarLaunch(arrayOf("-psn_0_1", "--once")))
        assertFalse(AgentMain.isExplorerStyleJarLaunch(arrayOf("--gui")))
    }

    @Test
    fun shadeOriginalThinBackupJarName() {
        assertTrue(AgentMain.isShadeOriginalThinBackupJarName("original-properbackup-agent-2.0.0-SNAPSHOT.jar"))
        assertFalse(AgentMain.isShadeOriginalThinBackupJarName("properbackup-agent-2.0.0-SNAPSHOT.jar"))
        assertFalse(AgentMain.isShadeOriginalThinBackupJarName(null))
    }

    @Test
    fun configRootBesideJarOrAppPlainJar() {
        val jar = Path.of("/tmp/agent/properbackup-agent.jar")
        assertEquals(Path.of("/tmp/agent"), AgentMain.configRootBesideJarOrApp(jar).orElseThrow())
    }

    @Test
    fun configRootBesideJarOrAppInsideMacAppBundle() {
        val jar = Path.of("/Users/x/Desktop/ProperBackup Agent.app/Contents/app/properbackup-agent.jar")
        assertEquals(Path.of("/Users/x/Desktop"), AgentMain.configRootBesideJarOrApp(jar).orElseThrow())
    }
}
