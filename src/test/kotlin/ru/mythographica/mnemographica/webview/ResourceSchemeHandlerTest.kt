package ru.mythographica.mnemographica.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ResourceSchemeHandlerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `decodes percent-encoded paths like the sidecar's asWebviewUri`() {
        assertEquals(
            "/Applications/OpenIDE Pro.app/Contents/plugins/x/sidecar/media/webview.js",
            ResourceSchemeHandler.decodePath("mnemo-res://file/Applications/OpenIDE%20Pro.app/Contents/plugins/x/sidecar/media/webview.js"),
        )
        assertEquals("/a/b+c/Ψ.css", ResourceSchemeHandler.decodePath("mnemo-res://file/a/b+c/%CE%A8.css?v=3#top"))
        assertEquals("C:/dir/file.js", ResourceSchemeHandler.decodePath("mnemo-res://file/C:/dir/file.js"))
        assertNull(ResourceSchemeHandler.decodePath("mnemo-res://other/a.js"))
        assertNull(ResourceSchemeHandler.decodePath("https://file/a.js"))
        assertNull(ResourceSchemeHandler.decodePath("mnemo-res://file/a%2"))
        assertNull(ResourceSchemeHandler.decodePath("mnemo-res://file/a%00b"))
    }

    @Test
    fun `urlFor and decodePath round-trip`() {
        val path = "/Applications/OpenIDE Pro.app/a b/c#d?e%f/ж.js"
        val url = ResourceSchemeHandler.urlFor(path)
        assertEquals(true, url.startsWith("mnemo-res://file/Applications/OpenIDE%20Pro.app/a%20b/"))
        assertEquals(path, ResourceSchemeHandler.decodePath(url))
    }

    @Test
    fun `serves only files under the roots`() {
        val root = temp.newFolder("media").toPath()
        val inside = Files.writeString(root.resolve("webview.js"), "ok")
        val outside = Files.writeString(temp.root.toPath().resolve("secret.txt"), "no")
        Files.createDirectories(temp.root.toPath().resolve("media-evil"))
        val sibling = Files.writeString(temp.root.toPath().resolve("media-evil/x.js"), "no")
        val roots = listOf(root)

        assertEquals(inside.toRealPath(), ResourceSchemeHandler.resolve(inside.toString(), roots))
        assertNull("outside", ResourceSchemeHandler.resolve(outside.toString(), roots))
        assertNull("dot-dot escape", ResourceSchemeHandler.resolve("$root/../secret.txt", roots))
        assertNull("prefix sibling", ResourceSchemeHandler.resolve(sibling.toString(), roots))
        assertNull("missing", ResourceSchemeHandler.resolve("$root/missing.js", roots))
        assertNull("directory", ResourceSchemeHandler.resolve(root.toString(), roots))
        assertNull("relative", ResourceSchemeHandler.resolve("media/webview.js", roots))
        assertNull("no roots", ResourceSchemeHandler.resolve(inside.toString(), emptyList()))

        val link = root.resolve("link.txt")
        Files.createSymbolicLink(link, outside)
        assertNull("symlink escape", ResourceSchemeHandler.resolve(link.toString(), roots))
    }

    @Test
    fun `mime types`() {
        assertEquals("text/javascript", ResourceSchemeHandler.mimeType("webview.js"))
        assertEquals("text/css", ResourceSchemeHandler.mimeType("webview.css"))
        assertEquals("image/svg+xml", ResourceSchemeHandler.mimeType("psi-icon.SVG"))
        assertEquals("image/png", ResourceSchemeHandler.mimeType("a.png"))
        assertEquals("application/json", ResourceSchemeHandler.mimeType("layout.json"))
        assertEquals("text/html", ResourceSchemeHandler.mimeType("index.html"))
        assertEquals("font/woff2", ResourceSchemeHandler.mimeType("codicon.woff2"))
        assertEquals("application/octet-stream", ResourceSchemeHandler.mimeType("blob"))
    }

    @Test
    fun `page urls carry page id and generation`() {
        assertEquals("mnemo-res://file/.mnemo-panel/7/index.html?v=2", ResourceSchemeHandler.pageUrl(7, 2))
        assertEquals("/.mnemo-panel/7/index.html", ResourceSchemeHandler.decodePath(ResourceSchemeHandler.pageUrl(7, 2)))
    }
}
