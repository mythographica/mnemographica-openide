package ru.mythographica.mnemographica.webview

import java.io.File

/**
 * The host-side rewrite of `webview/setHtml` (PROTOCOL.md, "Webview HTML rewrite"): bridge polyfill as the first
 * script in `<head>`, the `--vscode-*` theme block, the `vscode-dark` / `vscode-light` body class, and the CDN
 * d3/three URLs swapped for the vendored copies.
 *
 * The injected script and style precede any `Content-Security-Policy` meta, and a meta policy only governs what follows
 * it, so they need no CSP changes; a policy is relaxed only by adding `mnemo-res:` to `script-src` when a CDN script was
 * redirected there.
 */
object HtmlRewriter {
    /** The `acquireVsCodeApi` / `__mnemoDeliver` bridge; `window.__mnemoSend` is defined right before it. */
    const val POLYFILL = """window.acquireVsCodeApi = (() => { let api; return () => api ??= {
  postMessage: m => window.__mnemoSend(JSON.stringify(m)),
  getState: () => window.__mnemoState, setState: s => (window.__mnemoState = s)
}; })();
window.__mnemoDeliver = json => window.dispatchEvent(new MessageEvent('message', { data: JSON.parse(json) }));"""

    /** CDN URL in the upstream templates -> file name in `sidecar/dist/vendor/`. */
    val CDN_SCRIPTS = mapOf(
        "https://cdn.jsdelivr.net/npm/d3@7/dist/d3.min.js" to "d3.min.js",
        "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js" to "three.min.js",
    )

    private val HEAD_TAG = Regex("<head(?=[\\s>/])[^>]*>", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("<html(?=[\\s>])[^>]*>", RegexOption.IGNORE_CASE)
    private val BODY_TAG = Regex("<body(?=[\\s>/])[^>]*>", RegexOption.IGNORE_CASE)
    private val CLASS_ATTR = Regex("""\bclass\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
    private val CSP_META = Regex(
        """<meta\s[^>]*http-equiv\s*=\s*["']?Content-Security-Policy["']?[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val CONTENT_ATTR = Regex("""\bcontent\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

    /**
     * @param sendScript JS that defines `window.__mnemoSend(json)` (the JBCefJSQuery injection).
     * @param themeCss the `:root { --vscode-*: ... }` block from [ThemeCss].
     * @param vendorDir `sidecar/dist/vendor`; a CDN URL is rewritten only when its file is there and non-empty.
     */
    fun rewrite(html: String, sendScript: String, themeCss: String, dark: Boolean, vendorDir: File?): String {
        var result = html
        var redirected = false
        for ((cdnUrl, fileName) in CDN_SCRIPTS) {
            val vendored = vendorDir?.resolve(fileName)?.takeIf { it.isFile && it.length() > 0 } ?: continue
            if (result.contains(cdnUrl)) {
                result = result.replace(cdnUrl, ResourceSchemeHandler.urlFor(vendored.absolutePath))
                redirected = true
            }
        }
        if (redirected) result = allowResourceScripts(result)
        result = addBodyClass(result, if (dark) "vscode-dark" else "vscode-light")
        return injectHead(result, headInjection(sendScript, themeCss))
    }

    fun headInjection(sendScript: String, themeCss: String): String =
        "<meta charset=\"utf-8\">\n" +
            "<script>\n$sendScript\n$POLYFILL\n</script>\n" +
            "<style id=\"mnemo-theme\">\n$themeCss\n</style>\n"

    private fun injectHead(html: String, injection: String): String {
        HEAD_TAG.find(html)?.let { head -> return html.replaceRange(head.range.last + 1, head.range.last + 1, "\n$injection") }
        HTML_TAG.find(html)?.let { tag -> return html.replaceRange(tag.range.last + 1, tag.range.last + 1, "\n<head>\n$injection</head>") }
        return "<head>\n$injection</head>\n$html"
    }

    private fun addBodyClass(html: String, themeClass: String): String {
        val body = BODY_TAG.find(html) ?: return html
        val tag = body.value
        val classAttr = CLASS_ATTR.find(tag)
        val newTag = if (classAttr != null) {
            val (quote, classes) = classAttr.destructured
            tag.replaceRange(classAttr.range, "class=$quote${"$classes $themeClass".trim()}$quote")
        } else {
            tag.replaceFirst(Regex("^<body", RegexOption.IGNORE_CASE), "<body class=\"$themeClass\"")
        }
        return html.replaceRange(body.range, newTag)
    }

    /** Adds `mnemo-res:` to the policy's `script-src` (or `default-src`) when it does not allow it yet. */
    private fun allowResourceScripts(html: String): String = CSP_META.replace(html) { meta ->
        val content = CONTENT_ATTR.find(meta.value) ?: return@replace meta.value
        val policy = content.groupValues[1].ifEmpty { content.groupValues[2] }
        val relaxed = relaxPolicy(policy)
        if (relaxed == policy) {
            meta.value
        } else {
            meta.value.replaceRange(content.range, "content=\"${relaxed.replace("\"", "&quot;")}\"")
        }
    }

    fun relaxPolicy(policy: String): String {
        val directives = policy.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val names = directives.map { it.substringBefore(' ').lowercase() }
        val target = when {
            "script-src" in names -> "script-src"
            "default-src" in names -> "default-src"
            else -> return policy
        }
        val scheme = "${ResourceSchemeHandler.SCHEME}:"
        return directives.joinToString("; ") { directive ->
            val tokens = directive.split(Regex("\\s+"))
            if (tokens.first().lowercase() != target || scheme in tokens) {
                directive
            } else {
                // `'none'` must stand alone, so it gives way to the added source.
                (tokens.filter { it.lowercase() != "'none'" } + scheme).joinToString(" ")
            }
        }
    }
}
