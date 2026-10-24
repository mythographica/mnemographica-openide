package ru.mythographica.mnemographica.webview

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.jcef.JBCefApp
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeRegistrar
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves `mnemo-res://file/<percent-encoded absolute path>` (what the sidecar's `Webview.asWebviewUri` returns) from
 * disk, restricted to the requesting panel's `localResourceRoots` plus the vendored d3/three directory, and the
 * rewritten panel pages themselves under [PAGE_PREFIX].
 *
 * The scheme is registered as standard, secure, CORS- and fetch-enabled through [JBCefApp.addCefCustomSchemeHandlerFactory],
 * which only works before JCEF initializes; [ResourceSchemeRegistrar] calls [register] when the IDE frame is created.
 * When JCEF is already up, the factory is registered at runtime instead (a non-standard scheme: pages still load,
 * relative URLs and `fetch` may not).
 */
class ResourceSchemeHandler : JBCefApp.JBCefCustomSchemeHandlerFactory {
    override fun registerCustomScheme(registrar: CefSchemeRegistrar) {
        registrar.addCustomScheme(
            SCHEME,
            true, // standard: host + path, relative URLs resolve
            false, // local
            false, // display isolated
            true, // secure context
            true, // CORS enabled
            false, // CSP bypassing
            true, // fetch enabled (the graph's Export reads its own scripts back)
        )
    }

    override fun getSchemeName(): String = SCHEME

    override fun getDomainName(): String = HOST

    override fun create(browser: CefBrowser?, frame: CefFrame?, schemeName: String?, request: CefRequest): CefResourceHandler {
        val url = request.url ?: return Response.notFound("")
        val path = decodePath(url) ?: return Response.notFound(url).also { LOG.warn("Mnemonica: mnemo-res 404 (bad URL) $url") }
        WebviewPanelRegistry.pageFor(path)?.let { page -> return Response(200, "text/html", page.toByteArray(Charsets.UTF_8), url) }
        val roots = WebviewPanelRegistry.allowedRootsFor(browser)
        val file = resolve(path, roots)
        if (file == null) {
            LOG.warn("Mnemonica: mnemo-res 404 $url (missing or outside $roots)")
            return Response.notFound(url)
        }
        val bytes = try {
            Files.readAllBytes(file)
        } catch (e: java.io.IOException) {
            LOG.warn("Mnemonica: mnemo-res 404 $url: ${e.message}")
            return Response.notFound(url)
        }
        LOG.debug("Mnemonica: mnemo-res 200 $url (${bytes.size} bytes)")
        return Response(200, mimeType(file.fileName.toString()), bytes, url)
    }

    /** A fully buffered response; resources are small local files. */
    private class Response(
        private val status: Int,
        private val mime: String,
        private val body: ByteArray,
        private val url: String,
    ) : CefResourceHandlerAdapter() {
        private var offset = 0

        override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
            callback.Continue()
            return true
        }

        override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
            response.status = status
            response.statusText = if (status == 200) "OK" else "Not Found"
            response.mimeType = mime
            val contentType = if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")) {
                "$mime; charset=utf-8"
            } else {
                mime
            }
            response.setHeaderMap(
                mapOf(
                    "Content-Type" to contentType,
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "no-cache",
                ),
            )
            responseLength.set(body.size)
        }

        override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
            val count = minOf(bytesToRead, body.size - offset)
            if (count <= 0) {
                bytesRead.set(0)
                return false
            }
            System.arraycopy(body, offset, dataOut, 0, count)
            offset += count
            bytesRead.set(count)
            return true
        }

        override fun toString(): String = "mnemo-res $status $url"

        companion object {
            fun notFound(url: String) = Response(404, "text/plain", "Not found: $url".toByteArray(), url)
        }
    }

    companion object {
        private val LOG = logger<ResourceSchemeHandler>()
        const val SCHEME = "mnemo-res"
        const val HOST = "file"
        const val PREFIX = "$SCHEME://$HOST"

        /** Path prefix of the rewritten panel pages: `mnemo-res://file/.mnemo-panel/<pageId>/index.html`. */
        const val PAGE_PREFIX = "/.mnemo-panel/"

        private val registered = AtomicBoolean(false)

        /** Registers the scheme once per IDE session; safe to call from anywhere, any number of times. */
        fun register() {
            if (!registered.compareAndSet(false, true)) return
            val factory = ResourceSchemeHandler()
            try {
                JBCefApp.addCefCustomSchemeHandlerFactory(factory)
                LOG.info("Mnemonica: $SCHEME:// registered as a standard secure scheme (before JCEF startup)")
                return
            } catch (e: IllegalStateException) {
                LOG.info("Mnemonica: JCEF already initialized (${e.message}); registering $SCHEME:// at runtime")
            }
            if (!JBCefApp.isSupported()) return
            JBCefApp.getInstance()
            val ok = CefApp.getInstance().registerSchemeHandlerFactory(SCHEME, HOST, factory)
            LOG.warn("Mnemonica: $SCHEME:// registered at runtime as a non-standard scheme (ok=$ok); relative URLs and fetch may fail")
        }

        fun pageUrl(pageId: Int, generation: Int): String = "$PREFIX${PAGE_PREFIX}$pageId/index.html?v=$generation"

        /** `mnemo-res://file/<encoded path>` for an absolute local path, as the sidecar's `asWebviewUri` produces it. */
        fun urlFor(path: String): String {
            val unix = path.replace(File.separatorChar, '/')
            val absolute = if (unix.startsWith("/")) unix else "/$unix"
            return PREFIX + absolute.split('/').joinToString("/") { segment ->
                URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
            }
        }

        /** The decoded absolute path of a `mnemo-res://file/...` URL (query and fragment dropped), or null. */
        fun decodePath(url: String): String? {
            if (!url.startsWith("$PREFIX/")) return null
            val raw = url.substring(PREFIX.length).substringBefore('#').substringBefore('?')
            val decoded = percentDecode(raw) ?: return null
            if (decoded.contains('\u0000')) return null
            // Windows: mnemo-res://file/C:/dir/file -> C:/dir/file
            return if (Regex("^/[A-Za-z]:/").containsMatchIn(decoded)) decoded.substring(1) else decoded
        }

        /** The file behind [path] if it exists and lies under one of [roots] (after resolving `..` and symlinks). */
        fun resolve(path: String, roots: Collection<Path>): Path? {
            val target = try {
                Path.of(path)
            } catch (_: InvalidPathException) {
                return null
            }
            if (!target.isAbsolute || !Files.isRegularFile(target)) return null
            val real = target.toRealPath()
            return real.takeIf { candidate -> roots.any { root -> isUnder(candidate, root) } }
        }

        fun isUnder(file: Path, root: Path): Boolean {
            val normalRoot = if (Files.exists(root)) root.toRealPath() else root.toAbsolutePath().normalize()
            return file.toAbsolutePath().normalize().startsWith(normalRoot)
        }

        fun mimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
            "js", "mjs", "cjs" -> "text/javascript"
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }

        /** `%XX` decoding as UTF-8 that, unlike URLDecoder, leaves `+` alone; null on a malformed escape. */
        private fun percentDecode(text: String): String? {
            val out = ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 2 >= text.length) return null
                    val byte = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    out.write(byte)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return out.toString(Charsets.UTF_8)
        }
    }
}

/** Registers `mnemo-res://` when the IDE frame appears, ahead of the first JCEF browser. */
class ResourceSchemeRegistrar : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ResourceSchemeHandler.register()
    }
}
