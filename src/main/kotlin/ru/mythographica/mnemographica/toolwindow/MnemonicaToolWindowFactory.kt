package ru.mythographica.mnemographica.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import ru.mythographica.mnemographica.rpc.InitializeResult
import ru.mythographica.mnemographica.rpc.TreeSetMetaParams
import ru.mythographica.mnemographica.settings.MnemonicaConfigurable
import ru.mythographica.mnemographica.sidecar.SidecarService
import ru.mythographica.mnemographica.ui.LoggerConsole
import java.util.concurrent.ConcurrentHashMap

/** "Mnemonica" tool window: one tab per `InitializeResult.views` entry, then the console tabs. */
class MnemonicaToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = SidecarService.getInstance(project)
        val tabs = ViewTabRegistry.getInstance(project)
        tabs.attach(toolWindow)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            SidecarService.TOPIC,
            SidecarService.Listener { tabs.onStateChanged(it) },
        )
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                tabs.byContent(event.content)?.setVisible(event.operation == ContentManagerEvent.ContentOperation.add)
            }
        })
        tabs.onStateChanged(service.state)
        val console = LoggerConsole.getInstance(project)
        console.toolWindowReady = true
        console.attachAll()
        if (service.state == SidecarService.State.STOPPED) service.start()
    }

    companion object {
        const val ID = "Mnemonica"
    }
}

/** The live [ViewTab]s of a project, keyed by view id, plus the status tab shown while there are none. EDT only. */
@Service(Service.Level.PROJECT)
class ViewTabRegistry(private val project: Project) : Disposable {
    private val tabs = ConcurrentHashMap<String, ViewTab>()
    private val pendingMeta = ConcurrentHashMap<String, TreeSetMetaParams>()
    private var toolWindow: ToolWindow? = null
    private var statusContent: Content? = null
    private var builtFor: InitializeResult? = null

    fun get(viewId: String): ViewTab? = tabs[viewId]

    fun byContent(content: Content): ViewTab? = tabs.values.firstOrNull { it.content === content }

    fun attach(window: ToolWindow) {
        toolWindow = window
    }

    fun setMeta(params: TreeSetMetaParams) {
        val tab = tabs[params.viewId]
        if (tab == null) pendingMeta[params.viewId] = params else tab.setMeta(params)
    }

    fun onRegistered(viewId: String) {
        tabs[viewId]?.onRegistered()
    }

    /** Activates the tool window and selects the tab of [viewId] (`view/focus`). */
    fun focus(viewId: String) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(MnemonicaToolWindowFactory.ID) ?: return
        window.activate {
            tabs[viewId]?.content?.let { window.contentManager.setSelectedContent(it, true) }
        }
    }

    fun onStateChanged(state: SidecarService.State) {
        val window = toolWindow ?: return
        val service = SidecarService.getInstance(project)
        when (state) {
            SidecarService.State.RUNNING -> rebuild(window, service)
            SidecarService.State.STARTING -> if (tabs.isEmpty()) showStatus(window, "Starting Mnemonica sidecar…", service)
            else -> {
                val reason = service.problem ?: "Mnemonica sidecar is not running"
                if (tabs.isEmpty()) showStatus(window, reason, service) else tabs.values.forEach { it.markStale(reason) }
            }
        }
    }

    private fun rebuild(window: ToolWindow, service: SidecarService) {
        val result = service.initializeResult
        if (result != null && result === builtFor && tabs.isNotEmpty()) return
        builtFor = result
        val views = result?.views.orEmpty()
        val manager = window.contentManager
        removeViewTabs(window)
        views.forEachIndexed { index, view ->
            val tab = ViewTab(service, view)
            val content = ContentFactory.getInstance().createContent(tab.component, view.name, false)
            content.isCloseable = false
            content.setDisposer(tab)
            tab.content = content
            tabs[view.id] = tab
            manager.addContent(content, index)
            pendingMeta.remove(view.id)?.let(tab::setMeta)
            if (view.id in service.registeredViews) tab.onRegistered()
        }
        manager.getContent(0)?.let { manager.setSelectedContent(it) }
        LOG.info("Mnemonica view tabs registered: ${views.size} [${views.joinToString { it.id }}]")
    }

    private fun removeViewTabs(window: ToolWindow) {
        val manager = window.contentManager
        statusContent?.let { manager.removeContent(it, true) }
        statusContent = null
        tabs.values.forEach { tab -> tab.content?.let { manager.removeContent(it, true) } }
        tabs.clear()
    }

    private fun showStatus(window: ToolWindow, text: String, service: SidecarService) {
        val panel = JBPanelWithEmptyText()
        panel.emptyText.text = text
        if (service.state == SidecarService.State.NO_NODE || service.state == SidecarService.State.FAILED ||
            service.state == SidecarService.State.CRASHED
        ) {
            panel.emptyText.appendLine("Open settings", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, MnemonicaConfigurable::class.java)
            }
            panel.emptyText.appendLine("Restart sidecar", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { service.restart() }
        }
        val manager = window.contentManager
        statusContent?.let { manager.removeContent(it, true) }
        val content = ContentFactory.getInstance().createContent(panel, "Mnemonica", false)
        content.isCloseable = false
        statusContent = content
        manager.addContent(content, 0)
        manager.setSelectedContent(content)
    }

    override fun dispose() {
        tabs.values.forEach { Disposer.dispose(it) }
        tabs.clear()
    }

    companion object {
        private val LOG = logger<ViewTabRegistry>()

        fun getInstance(project: Project): ViewTabRegistry = project.getService(ViewTabRegistry::class.java)
    }
}
