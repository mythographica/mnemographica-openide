package ru.mythographica.mnemographica.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WhenClauseTest {
    private fun eval(clause: String?, vararg context: Pair<String, Any?>) = WhenClause.evaluate(clause, mapOf(*context))

    @Test
    fun `view equality`() {
        assertTrue(eval("view == mnemonicaTypes", "view" to "mnemonicaTypes"))
        assertFalse(eval("view == mnemonicaTypes", "view" to "mnemonicaUsages"))
        assertTrue(eval("view != mnemonicaTypes", "view" to "mnemonicaUsages"))
    }

    @Test
    fun `view and viewItem conjunction from view-item-context`() {
        val clause = "view == mnemonicaTypes && viewItem == navigable"
        assertTrue(eval(clause, "view" to "mnemonicaTypes", "viewItem" to "navigable"))
        assertFalse(eval(clause, "view" to "mnemonicaTypes", "viewItem" to "info"))
        assertFalse(eval(clause, "view" to "mnemonicaTypes"))
    }

    @Test
    fun `regex match with anchors and flags`() {
        val clause = "view == mnemonicaLiveTrace && viewItem =~ /^mnemTrace/"
        assertTrue(eval(clause, "view" to "mnemonicaLiveTrace", "viewItem" to "mnemTraceJaeger"))
        assertTrue(eval(clause, "view" to "mnemonicaLiveTrace", "viewItem" to "mnemTrace"))
        assertFalse(eval(clause, "view" to "mnemonicaLiveTrace", "viewItem" to "xmnemTrace"))
        assertTrue(eval("viewItem =~ /JAEGER$/i", "viewItem" to "mnemTraceJaeger"))
        assertTrue(eval("viewItem =~ /a\\/b/", "viewItem" to "a/b"))
    }

    @Test
    fun `negation, or, parentheses and precedence`() {
        assertTrue(eval("!flag", "flag" to false))
        assertFalse(eval("!flag", "flag" to true))
        assertTrue(eval("!missing"))
        assertTrue(eval("a || b && c", "a" to true))
        assertFalse(eval("(a || b) && c", "a" to true))
        assertTrue(eval("!(view == x) && view == y", "view" to "y"))
    }

    @Test
    fun `setContext keys, literals and quoted values`() {
        assertTrue(eval("mnemographica.loaded", "mnemographica.loaded" to true))
        assertFalse(eval("mnemographica.loaded"))
        assertTrue(eval("count == 3", "count" to 3.0))
        assertTrue(eval("flag == true", "flag" to true))
        assertTrue(eval("view == 'my view'", "view" to "my view"))
        assertTrue(eval("true"))
        assertFalse(eval("false || false"))
    }

    @Test
    fun `blank is true, malformed is false`() {
        assertTrue(eval(null))
        assertTrue(eval("  "))
        assertFalse(eval("view == ", "view" to "x"))
        assertFalse(eval("(view == x", "view" to "x"))
        assertFalse(eval("viewItem =~ /[/", "viewItem" to "x"))
    }

    @Test
    fun `every when clause in the extension's package json parses`() {
        val packageJson = File("mnemographica/package.json").takeIf { it.isFile } ?: return
        val clauses = Regex("\"when\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(packageJson.readText())
            .map { it.groupValues[1].replace("\\\\", "\\") }
            .toList()
        assertTrue(clauses.isNotEmpty())
        clauses.forEach { WhenClause.parse(it) }
        assertEquals(15, clauses.size)
    }
}
