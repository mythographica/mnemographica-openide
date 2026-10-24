package ru.mythographica.mnemographica.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbAware
import ru.mythographica.mnemographica.rpc.CommandInfo
import ru.mythographica.mnemographica.rpc.InitializeResult
import ru.mythographica.mnemographica.rpc.TreeItemRef
import ru.mythographica.mnemographica.sidecar.SidecarService
import ru.mythographica.mnemographica.toolwindow.IconMapper

/**
 * Runs one sidecar command through `command/execute` in the project of the event.
 * [context] is the tree item a context-menu invocation targets.
 */
class SidecarCommandAction(
    private val info: CommandInfo,
    private val context: TreeItemRef? = null,
) : AnAction(info.title.ifBlank { info.command }, commandDescription(info), IconMapper.codicon(info.icon)),
    DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && SidecarService.getInstance(project).remote != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SidecarService.getInstance(project).executeCommand(info.command, emptyList(), context)
    }

    companion object {
        fun commandDescription(info: CommandInfo): String =
            info.category?.let { "$it: ${info.title}" } ?: info.title
    }
}

/**
 * Registers every [CommandInfo] as an application action `Mnemonica.<command>` (findable in Search Everywhere)
 * and puts the `commandPalette` ones into the Tools | Mnemonica group. Idempotent across projects; EDT.
 */
object DynamicCommandActions {
    const val ACTION_PREFIX = "Mnemonica."
    const val TOOLS_GROUP_ID = "Mnemonica.ToolsGroup"
    private val LOG = logger<DynamicCommandActions>()

    fun register(result: InitializeResult) {
        val manager = ActionManager.getInstance()
        val pluginId = PluginId.getId(SidecarService.PLUGIN_ID)
        val group = manager.getAction(TOOLS_GROUP_ID) as? DefaultActionGroup
        val palette = result.menus.commandPalette.map { it.command }.toSet()
        var registered = 0
        for (info in result.commands) {
            if (info.internal == true) continue
            val id = ACTION_PREFIX + info.command
            if (manager.getAction(id) == null) {
                manager.registerAction(id, SidecarCommandAction(info), pluginId)
                registered++
            }
            val action = manager.getAction(id) ?: continue
            val inGroup = palette.isEmpty() || info.command in palette
            if (inGroup && group != null && !group.containsAction(action)) group.add(action)
        }
        LOG.info("Mnemonica: registered $registered command actions (${result.commands.size} commands)")
    }

    fun actionFor(command: String): AnAction? = ActionManager.getInstance().getAction(ACTION_PREFIX + command)
}
