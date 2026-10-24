package ru.mythographica.mnemographica.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Consumer
import ru.mythographica.mnemographica.rpc.StatusBarUpdateParams
import ru.mythographica.mnemographica.sidecar.SidecarService
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * One status bar widget that shows every visible `statusBar/update` item of the sidecar, highest priority first.
 * The extension creates a single item ("Ψ"), so one widget keeps it simple; a click runs the first item's command.
 */
class MnemonicaStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = "Mnemonica"

    override fun createWidget(project: Project): StatusBarWidget = MnemonicaStatusBarWidget(project)

    companion object {
        const val ID = "MnemonicaStatus"

        /** Re-renders the widget after a `statusBar/update`; EDT. */
        fun update(project: Project) {
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
        }

        /** `$(icon) text` -> `text`: codicons have no status bar rendering here. */
        fun stripIcons(text: String): String = text.replace(Regex("\\$\\([a-z0-9~-]+\\)\\s*"), "").trim()
    }
}

private class MnemonicaStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {
    override fun ID(): String = MnemonicaStatusBarWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) = Unit

    private fun items(): List<StatusBarUpdateParams> =
        SidecarService.getInstance(project).statusItems.values
            .filter { it.visible }
            .sortedByDescending { it.priority ?: 0.0 }

    override fun getText(): String = items().joinToString("  ") { MnemonicaStatusBarWidgetFactory.stripIcons(it.text) }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String? = items().mapNotNull { it.tooltip }.joinToString("\n").ifEmpty { null }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        val command = items().firstNotNullOfOrNull { it.command } ?: return@Consumer
        SidecarService.getInstance(project).executeCommand(command)
    }
}
