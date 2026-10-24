package ru.mythographica.mnemographica.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HtmlRewriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val send = "window.__mnemoSend = function (json) { window.cefQuery_1({request: '' + json}); };"
    private val theme = ":root {\n  --vscode-foreground: #cccccc;\n}"

    private val d3 = "https://cdn.jsdelivr.net/npm/d3@7/dist/d3.min.js"
    private val three = "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js"

    /** Shaped like mnemographica/src/webview/panel.ts. */
    private val graphPage = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        	<meta charset="UTF-8">
        	<title>Mnemonica Graph</title>
        	<link rel="stylesheet" href="mnemo-res://file/Applications/OpenIDE%20Pro.app/media/webview.css">
        	<script src="$d3"></script>
        	<script src="$three"></script>
        </head>
        <body>
        	<header id="h"></header>
        	<script>const SHOW_PROPERTIES_PLACEHOLDER = true;</script>
        	<script src="mnemo-res://file/Applications/OpenIDE%20Pro.app/media/webview.js"></script>
        </body>
        </html>
    """.trimIndent()

    private fun vendorDir(): File = temp.newFolder("vendor").apply {
        resolve("d3.min.js").writeText("/* d3 */")
        resolve("three.min.js").writeText("/* three */")
    }

    private fun scripts(html: String): List<String> = Regex("<script[^>]*>").findAll(html).map { it.value }.toList()

    @Test
    fun `polyfill is the first script in head and defines the bridge`() {
        val html = HtmlRewriter.rewrite(graphPage, send, theme, dark = true, vendorDir = null)
        val head = html.substring(html.indexOf("<head>"), html.indexOf("</head>"))
        val firstScript = head.indexOf("<script")
        assertEquals("the bridge is the first script", head.indexOf("<script>\n$send"), firstScript)
        assertTrue(head.indexOf(HtmlRewriter.POLYFILL) > firstScript)
        assertTrue("before the page's own scripts", head.indexOf(HtmlRewriter.POLYFILL) < head.indexOf("<script src="))
        assertTrue(html.contains("window.acquireVsCodeApi = "))
        assertTrue(html.contains("window.__mnemoDeliver = json => window.dispatchEvent(new MessageEvent('message'"))
        assertTrue("theme block injected", head.contains("<style id=\"mnemo-theme\">\n$theme\n</style>"))
        assertEquals("<header> is not mistaken for <head>", 1, Regex("mnemo-theme").findAll(html).count())
    }

    @Test
    fun `CDN scripts are rewritten to the vendored copies when present`() {
        val vendor = vendorDir()
        val html = HtmlRewriter.rewrite(graphPage, send, theme, dark = true, vendorDir = vendor)
        assertFalse(html.contains(d3))
        assertFalse(html.contains(three))
        val expectedD3 = ResourceSchemeHandler.urlFor(vendor.resolve("d3.min.js").absolutePath)
        assertTrue(expectedD3.startsWith("mnemo-res://file/"))
        assertTrue(scripts(html).contains("<script src=\"$expectedD3\">"))
        assertTrue(scripts(html).contains("<script src=\"${ResourceSchemeHandler.urlFor(vendor.resolve("three.min.js").absolutePath)}\">"))
    }

    @Test
    fun `CDN scripts stay when nothing is vendored`() {
        val empty = temp.newFolder("empty-vendor").apply { resolve("d3.min.js").writeText("") }
        val html = HtmlRewriter.rewrite(graphPage, send, theme, dark = false, vendorDir = empty)
        assertTrue("an empty file is not a vendored copy", html.contains(d3))
        assertTrue(html.contains(three))
    }

    @Test
    fun `body gets the theme class`() {
        assertTrue(HtmlRewriter.rewrite(graphPage, send, theme, dark = true, vendorDir = null).contains("<body class=\"vscode-dark\">"))
        assertTrue(HtmlRewriter.rewrite(graphPage, send, theme, dark = false, vendorDir = null).contains("<body class=\"vscode-light\">"))
        val withClass = HtmlRewriter.rewrite("<html><head></head><body class='x' id=b></body></html>", send, theme, true, null)
        assertTrue(withClass, withClass.contains("<body class='x vscode-dark' id=b>"))
    }

    @Test
    fun `CSP gains mnemo-res for redirected CDN scripts and is otherwise kept`() {
        val csp = """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src https://cdn.jsdelivr.net 'nonce-abc'; style-src mnemo-res: 'unsafe-inline'">"""
        val page = graphPage.replace("<meta charset=\"UTF-8\">", "<meta charset=\"UTF-8\">\n$csp")
        val html = HtmlRewriter.rewrite(page, send, theme, dark = true, vendorDir = vendorDir())
        assertTrue(
            html,
            html.contains("""content="default-src 'none'; script-src https://cdn.jsdelivr.net 'nonce-abc' mnemo-res:; style-src mnemo-res: 'unsafe-inline'""""),
        )
        assertTrue("the injected bridge precedes the policy, so it is not governed by it", html.indexOf(HtmlRewriter.POLYFILL) < html.indexOf("Content-Security-Policy"))

        val untouched = HtmlRewriter.rewrite(page, send, theme, dark = true, vendorDir = null)
        assertTrue("no redirect, no CSP change", untouched.contains(csp))
    }

    @Test
    fun `relaxPolicy falls back to default-src and drops none`() {
        assertEquals("default-src mnemo-res:; img-src *", HtmlRewriter.relaxPolicy("default-src 'none'; img-src *"))
        assertEquals("script-src 'self' mnemo-res:", HtmlRewriter.relaxPolicy("script-src 'self' mnemo-res:"))
        assertEquals("img-src *", HtmlRewriter.relaxPolicy("img-src *"))
    }

    @Test
    fun `pages without head or html get one`() {
        val inline = HtmlRewriter.rewrite("<body><script>acquireVsCodeApi()</script></body>", send, theme, true, null)
        assertTrue(inline.startsWith("<head>\n<meta charset=\"utf-8\">\n<script>\n$send"))
        val noHead = HtmlRewriter.rewrite("<html><body></body></html>", send, theme, true, null)
        assertTrue(noHead, noHead.startsWith("<html>\n<head>\n<meta charset"))
    }
}
