package pl.danielniemiec.properbackup.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentMainCliTest {

    @Test
    fun `isExplorerStyleJarLaunch returns true for empty args`() {
        assertTrue(AgentMain.isExplorerStyleJarLaunch(emptyArray()))
    }

    @Test
    fun `isExplorerStyleJarLaunch returns false for headless flag`() {
        assertFalse(AgentMain.isExplorerStyleJarLaunch(arrayOf("--headless")))
    }

    @Test
    fun `isExplorerStyleJarLaunch returns false for install-service`() {
        assertFalse(AgentMain.isExplorerStyleJarLaunch(arrayOf("--install-service")))
    }

    @Test
    fun `isExplorerStyleJarLaunch returns false for activate`() {
        assertFalse(AgentMain.isExplorerStyleJarLaunch(arrayOf("--activate", "TOKEN123")))
    }

    @Test
    fun `isExplorerStyleJarLaunch returns true for macOS noise args`() {
        assertTrue(AgentMain.isExplorerStyleJarLaunch(arrayOf("-psn_0_12345")))
        assertTrue(AgentMain.isExplorerStyleJarLaunch(arrayOf("-XstartOnFirstThread")))
    }

    @Test
    fun `implicitGuiJarLaunchNoiseArg recognizes macOS args`() {
        assertTrue(AgentMain.implicitGuiJarLaunchNoiseArg("-psn_0_12345"))
        assertTrue(AgentMain.implicitGuiJarLaunchNoiseArg("-XstartOnFirstThread"))
        assertTrue(AgentMain.implicitGuiJarLaunchNoiseArg("-Xdock:name=test"))
        assertTrue(AgentMain.implicitGuiJarLaunchNoiseArg("-Dapple.awt.UIElement=true"))
        assertFalse(AgentMain.implicitGuiJarLaunchNoiseArg("--headless"))
    }
}
