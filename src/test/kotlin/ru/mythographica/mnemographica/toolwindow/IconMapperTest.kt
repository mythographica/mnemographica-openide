package ru.mythographica.mnemographica.toolwindow

import com.intellij.icons.AllIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IconMapperTest {
    @Test
    fun `codicon ids are normalised`() {
        assertEquals("refresh", IconMapper.codiconId("$(refresh)"))
        assertEquals("sync", IconMapper.codiconId("sync~spin"))
        assertEquals("plug", IconMapper.codiconId(" plug "))
    }

    @Test
    fun `command icons map to platform icons`() {
        assertSame(AllIcons.Actions.Refresh, IconMapper.codicon("$(refresh)"))
        assertSame(AllIcons.General.Settings, IconMapper.codicon("$(settings-gear)"))
        assertSame(AllIcons.Actions.EditSource, IconMapper.codicon("$(go-to-file)"))
        assertSame(AllIcons.Nodes.Class, IconMapper.codicon("symbol-class"))
    }

    @Test
    fun `every codicon the extension uses is mapped`() {
        val used = listOf(
            "clear-all", "debug-alt", "file-code", "folder", "folder-opened", "go-to-file", "graph", "link-external",
            "list-tree", "output", "plug", "references", "refresh", "run", "server-environment", "server-process",
            "settings-gear", "symbol-method", "symbol-class", "pulse", "info", "warning", "circle-outline",
            "symbol-misc", "question", "broadcast",
        )
        used.forEach { assertNotNull("unmapped codicon $it", IconMapper.codicon(it)) }
    }

    @Test
    fun `unknown ids fall back`() {
        assertNull(IconMapper.codicon("no-such-codicon"))
        assertNull(IconMapper.codicon(null))
        assertSame(IconMapper.FALLBACK, IconMapper.codiconOrFallback("no-such-codicon"))
    }

    @Test
    fun `tree node icons`() {
        assertNull(IconMapper.forTreeNode(null, null, null))
        assertSame(AllIcons.Nodes.Method, IconMapper.forTreeNode("symbol-method", "/no/such/icon.svg", null))
        assertSame(IconMapper.FALLBACK, IconMapper.forTreeNode("no-such-codicon", null, null))
        assertNotNull(IconMapper.themeColor("errorForeground"))
        assertNull(IconMapper.themeColor("no.such.color"))
    }
}
