package ru.mythographica.mnemographica.webview

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import ru.mythographica.mnemographica.rpc.WebviewCreateParams
import ru.mythographica.mnemographica.rpc.WebviewDisposedParams
import ru.mythographica.mnemographica.rpc.WebviewMessageParams
import ru.mythographica.mnemographica.rpc.WebviewVisibilityParams
import ru.mythographica.mnemographica.sidecar.SidecarService
import ru.mythographica.mnemographica.sidecar.UiDispatcher
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-project map panelId -> [WebviewPanel] behind the `webview/...` notifications. Tab selection becomes
 * `webview/visibilityChanged`, a tab closed by the user becomes `webview/disposed`; `webview/dispose` from the sidecar
 * closes the tab without that echo. All panels close when the sidecar stops, since a new sidecar reuses the ids.
 */
@Service(Service.Level.PROJECT)
class WebviewPanelRegistry(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val panels = ConcurrentHashMap<String, WebviewPanel>()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = updateVisibility(event.manager)

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file !is WebviewVirtualFile || source.isFileOpen(file)) return
                val panel = panels[file.panelId]?.takeIf { it.file === file } ?: return
                if (!panels.remove(file.panelId, panel)) return
                LOG.info("Mnemonica: webview ${panel.panelId} closed by the user -> webview/disposed")
                SidecarService.getInstance(project).remote?.webviewDisposed(WebviewDisposedParams(panel.panelId))
                Disposer.dispose(panel)
            }
        })
        connection.subscribe(SidecarService.TOPIC, SidecarService.Listener { state ->
            if (state != SidecarService.State.RUNNING && state != SidecarService.State.STARTING) closeAll()
        })
    }

    fun get(panelId: String): WebviewPanel? = panels[panelId]

    /** EDT. Creates the browser and opens the tab. */
    fun create(params: WebviewCreateParams) {
        panels.remove(params.panelId)?.let(::close)
        ResourceSchemeHandler.register()
        val panel = WebviewPanel(project, scope, params, File(SidecarService.sidecarMain().parentFile, "vendor"))
        Disposer.register(this, panel)
        panels[params.panelId] = panel
        FileEditorManager.getInstance(project).openFile(panel.file, true)
        LOG.info(
            "Mnemonica: webview/create ${params.panelId} '${params.title}' (${params.viewType}) opened as page " +
                "${panel.pageId}, roots ${params.localResourceRoots}" + (panel.unsupportedReason?.let { "; $it" } ?: ""),
        )
    }

    /** Any thread. */
    fun setHtml(panelId: String, html: String) = withPanel(panelId, "webview/setHtml") { it.setHtml(html) }

    /** Any thread. */
    fun postMessage(panelId: String, message: JsonElement?) = withPanel(panelId, "webview/postMessage") { it.postMessage(message) }

    /** EDT. */
    fun reveal(panelId: String, preserveFocus: Boolean) = withPanel(panelId, "webview/reveal") {
        FileEditorManager.getInstance(project).openFile(it.file, !preserveFocus)
    }

    /** EDT. */
    fun setTitle(panelId: String, title: String) = withPanel(panelId, "webview/setTitle") {
        it.file.title = title
        (FileEditorManager.getInstance(project) as? FileEditorManagerEx)?.updateFilePresentation(it.file)
    }

    /** EDT. Closes the tab; the sidecar already disposed the panel, so no `webview/disposed` goes back. */
    fun dispose(panelId: String) {
        val panel = panels.remove(panelId) ?: return
        LOG.info("Mnemonica: webview/dispose $panelId -> tab closed")
        close(panel)
    }

    private fun close(panel: WebviewPanel) {
        FileEditorManager.getInstance(project).closeFile(panel.file)
        Disposer.dispose(panel)
    }

    private fun closeAll() {
        val all = panels.values.toList()
        panels.clear()
        all.forEach(::close)
    }

    private fun updateVisibility(manager: FileEditorManager) {
        val selected = manager.selectedFiles.toSet()
        val active = manager.selectedEditor?.file
        for (panel in panels.values) {
            panel.setViewState(visible = panel.file in selected, active = panel.file == active)
        }
    }

    private inline fun withPanel(panelId: String, method: String, action: (WebviewPanel) -> Unit) {
        val panel = panels[panelId]
        if (panel == null) {
            LOG.info("Mnemonica: $method for unknown panel $panelId ignored")
        } else {
            action(panel)
        }
    }

    override fun dispose() {
        panels.clear()
    }

    companion object {
        private val LOG = logger<WebviewPanelRegistry>()

        /** Live panels of all projects by page id; the scheme handler is application-wide. */
        private val pages = ConcurrentHashMap<Int, WebviewPanel>()

        fun getInstance(project: Project): WebviewPanelRegistry = project.getService(WebviewPanelRegistry::class.java)

        internal fun track(panel: WebviewPanel) {
            pages[panel.pageId] = panel
        }

        internal fun untrack(panel: WebviewPanel) {
            pages.remove(panel.pageId, panel)
        }

        /** The rewritten html for a `/.mnemo-panel/<pageId>/index.html` path of a live panel, else null. */
        fun pageFor(path: String): String? {
            if (!path.startsWith(ResourceSchemeHandler.PAGE_PREFIX)) return null
            val pageId = path.removePrefix(ResourceSchemeHandler.PAGE_PREFIX).substringBefore('/').toIntOrNull()
            return pageId?.let { pages[it]?.page }
        }

        /** The roots of the panel that owns [browser], or of every live panel when the request cannot be attributed. */
        fun allowedRootsFor(browser: CefBrowser?): List<Path> {
            val owner = pages.values.firstOrNull { it.owns(browser) }
            return (owner?.let(::listOf) ?: pages.values).flatMap { it.allowedRoots }.distinct()
        }
    }
}

/**
 * One sidecar webview: a JCEF browser showing the rewritten html as `mnemo-res://file/.mnemo-panel/<pageId>/index.html`
 * (same origin as its resources), a JBCefJSQuery bound as `window.__mnemoSend`, and a queue that holds
 * `webview/postMessage` until the current page finished loading.
 */
class WebviewPanel(
    private val project: Project,
    private val scope: CoroutineScope,
    params: WebviewCreateParams,
    private val vendorDir: File,
) : Disposable {
    val panelId: String = params.panelId
    val pageId: Int = NEXT_PAGE_ID.incrementAndGet()
    val file = WebviewVirtualFile(panelId, params.title)
    val allowedRoots: List<Path> = (params.localResourceRoots + vendorDir.path).mapNotNull(::toPath)

    val unsupportedReason: String? =
        if (JBCefApp.isSupported()) null else "JCEF (the embedded Chromium browser) is not available in this IDE runtime."

    val browser: JBCefBrowser? = if (unsupportedReason == null) JBCefBrowser.createBuilder().build() else null
    private val query: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }

    /** The rewritten html of the current generation, served by [ResourceSchemeHandler]. */
    @Volatile
    var page: String? = null
        private set

    @Volatile
    private var disposed = false

    private val lock = Any()
    private var generation = 0
    private var loaded = false
    private val pending = ArrayList<String>()

    private var visible = true
    private var active = true

    init {
        WebviewPanelRegistry.track(this)
        if (browser != null && query != null) {
            Disposer.register(this, browser)
            Disposer.register(this, query)
            query.addHandler { json ->
                onPageMessage(json)
                null
            }
            val client = browser.jbCefClient
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (frame.isMain) onPageLoaded(frame.url, httpStatusCode)
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    errorCode: CefLoadHandler.ErrorCode,
                    errorText: String?,
                    failedUrl: String?,
                ) {
                    LOG.warn("Mnemonica: webview $panelId failed to load $failedUrl: $errorCode $errorText")
                }
            }, browser.cefBrowser)
            client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    cefBrowser: CefBrowser,
                    level: CefSettings.LogSeverity,
                    message: String,
                    source: String,
                    line: Int,
                ): Boolean {
                    LOG.info("[webview $panelId] $level: $message ($source:$line)")
                    return false
                }
            }, browser.cefBrowser)
        }
    }

    fun owns(cefBrowser: CefBrowser?): Boolean = cefBrowser != null && browser?.cefBrowser === cefBrowser

    /** Any thread: the queue starts holding messages now; the rewrite and the load happen on the EDT. */
    fun setHtml(html: String) {
        val browser = browser ?: return
        val query = query ?: return
        val current = synchronized(lock) {
            loaded = false
            ++generation
        }
        scope.launch(UiDispatcher) {
            if (disposed) return@launch
            val send = "window.__mnemoSend = function (json) { ${query.inject("json")} };"
            page = HtmlRewriter.rewrite(html, send, ThemeCss.css(), ThemeCss.isDark(), vendorDir)
            val url = ResourceSchemeHandler.pageUrl(pageId, current)
            LOG.info("Mnemonica: webview/setHtml $panelId: ${html.length} chars rewritten to ${page?.length}, loading $url")
            browser.loadURL(url)
        }
    }

    /** Any thread. */
    fun postMessage(message: JsonElement?) {
        val json = GSON.toJson(message ?: JsonNull.INSTANCE)
        val now = synchronized(lock) {
            if (!loaded) pending += json
            loaded
        }
        if (now) deliver(json) else LOG.info("Mnemonica: webview/postMessage $panelId queued until the page loads: ${json.take(80)}")
    }

    /** EDT. */
    fun setViewState(visible: Boolean, active: Boolean) {
        if (visible == this.visible && active == this.active) return
        this.visible = visible
        this.active = active
        LOG.info("Mnemonica: webview $panelId visible=$visible active=$active")
        SidecarService.getInstance(project).remote?.webviewVisibilityChanged(WebviewVisibilityParams(panelId, visible, active))
    }

    private fun onPageLoaded(url: String?, status: Int) {
        val loadedGeneration = url?.substringAfter("?v=", "")?.toIntOrNull()
        val queued = synchronized(lock) {
            if (loadedGeneration != generation) return
            loaded = true
            pending.toList().also { pending.clear() }
        }
        LOG.info("Mnemonica: webview $panelId page loaded (HTTP $status) $url; delivering ${queued.size} queued messages")
        browser?.cefBrowser?.executeJavaScript(
            "console.info('mnemo page ready: d3 ' + typeof d3 + ', THREE ' + typeof THREE + ', acquireVsCodeApi ' + typeof acquireVsCodeApi)",
            url,
            0,
        )
        queued.forEach(::deliver)
    }

    private fun deliver(json: String) {
        val browser = browser ?: return
        browser.cefBrowser.executeJavaScript("window.__mnemoDeliver(${GSON.toJson(json)})", browser.cefBrowser.url, 0)
        LOG.info("Mnemonica: webview/postMessage $panelId delivered: ${json.take(80)}")
    }

    private fun onPageMessage(json: String) {
        if (disposed) return
        val message = try {
            JsonParser.parseString(json)
        } catch (e: com.google.gson.JsonParseException) {
            LOG.warn("Mnemonica: webview $panelId sent invalid JSON: ${e.message}")
            return
        }
        LOG.info("Mnemonica: webview/message $panelId ${json.take(80)}")
        SidecarService.getInstance(project).remote?.webviewMessage(WebviewMessageParams(panelId, message))
    }

    override fun dispose() {
        disposed = true
        WebviewPanelRegistry.untrack(this)
    }

    companion object {
        private val LOG = logger<WebviewPanel>()
        private val NEXT_PAGE_ID = AtomicInteger()

        /** serializeNulls: `{ focusedNode: null }` must reach the page as written. */
        private val GSON = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

        private fun toPath(path: String): Path? = try {
            Path.of(path)
        } catch (_: InvalidPathException) {
            null
        }
    }
}
