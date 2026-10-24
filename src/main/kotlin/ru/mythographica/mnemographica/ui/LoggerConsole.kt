package ru.mythographica.mnemographica.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import ru.mythographica.mnemographica.toolwindow.MnemonicaToolWindowFactory

/**
 * One console per `output/append` channel plus "Mnemonica Sidecar" for the sidecar's stderr,
 * shown as tabs of the Mnemonica tool window. Consoles exist before the tool window is opened.
 */
@Service(Service.Level.PROJECT)
class LoggerConsole(private val project: Project) : Disposable {
    private val consoles = linkedMapOf<String, ConsoleView>()
    private val contents = mutableMapOf<String, Content>()

    /** Set by the tool window factory once its content exists; consoles are attached from then on. */
    @Volatile
    var toolWindowReady = false

    fun appendSidecar(line: String) {
        LOG.debug("[sidecar stderr] $line")
        append(SIDECAR_CHANNEL, line + "\n", ConsoleViewContentType.LOG_INFO_OUTPUT)
    }

    fun append(channel: String, text: String, type: ConsoleViewContentType = ConsoleViewContentType.NORMAL_OUTPUT) {
        ApplicationManager.getApplication().invokeLater({ console(channel).print(text, type) }, ModalityState.any(), project.disposed)
    }

    fun show(channel: String, preserveFocus: Boolean) {
        ApplicationManager.getApplication().invokeLater({
            console(channel)
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(MnemonicaToolWindowFactory.ID) ?: return@invokeLater
            toolWindow.activate({
                toolWindowReady = true
                attachAll()
                contents[channel]?.let { toolWindow.contentManager.setSelectedContent(it, !preserveFocus) }
            }, !preserveFocus)
        }, project.disposed)
    }

    /** Adds the consoles that are not in the tool window yet; EDT. */
    fun attachAll() {
        if (!toolWindowReady) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(MnemonicaToolWindowFactory.ID) ?: return
        val manager = toolWindow.contentManager
        if (manager.isDisposed) return
        for ((channel, console) in consoles) {
            if (contents[channel]?.let { manager.getIndexOfContent(it) >= 0 } == true) continue
            val content = ContentFactory.getInstance().createContent(console.component, channel, false)
            content.isCloseable = false
            content.setDisposer { contents.remove(channel) }
            manager.addContent(content)
            contents[channel] = content
        }
    }

    private fun console(channel: String): ConsoleView = consoles.getOrPut(channel) {
        TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also {
            Disposer.register(this, it)
            if (toolWindowReady) ApplicationManager.getApplication().invokeLater({ attachAll() }, ModalityState.any(), project.disposed)
        }
    }

    override fun dispose() {
        consoles.clear()
        contents.clear()
    }

    companion object {
        private val LOG = logger<LoggerConsole>()
        const val SIDECAR_CHANNEL = "Mnemonica Sidecar"

        fun getInstance(project: Project): LoggerConsole = project.getService(LoggerConsole::class.java)
    }
}
