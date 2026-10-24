package ru.mythographica.mnemographica.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import ru.mythographica.mnemographica.rpc.CommandInfo
import ru.mythographica.mnemographica.rpc.MenuItem
import ru.mythographica.mnemographica.rpc.TreeItemRef
import ru.mythographica.mnemographica.sidecar.SidecarService
import ru.mythographica.mnemographica.toolwindow.JsonTreeModel
import ru.mythographica.mnemographica.toolwindow.WhenClause

/** `when` context: setContext keys, `view`, and `viewItem` when a tree item is involved. */
internal fun whenContext(service: SidecarService, viewId: String, viewItem: String?): Map<String, Any?> {
    val context = HashMap<String, Any?>()
    for ((key, value) in service.contextKeys) {
        context[key] = when {
            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
            value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asDouble
            value.isJsonPrimitive -> value.asString
            value.isJsonNull -> null
            else -> value.toString()
        }
    }
    context["view"] = viewId
    if (viewItem != null) context["viewItem"] = viewItem
    return context
}

/**
 * Menu items whose `when` matches, ordered by group then `@order`, with separators between groups
 * ("navigation" first, like VS Code).
 */
internal fun menuActions(
    service: SidecarService,
    items: List<MenuItem>,
    context: Map<String, Any?>,
    target: TreeItemRef?,
): Array<AnAction> {
    val commands: Map<String, CommandInfo> = service.initializeResult?.commands.orEmpty().associateBy { it.command }
    val matching = items
        .filter { WhenClause.evaluate(it.`when`, context) }
        .sortedWith(compareBy<MenuItem>({ groupName(it) != "navigation" }, ::groupName, ::groupOrder))
    val result = ArrayList<AnAction>()
    var lastGroup: String? = null
    for (item in matching) {
        val info = commands[item.command] ?: CommandInfo(item.command, item.command)
        val group = groupName(item)
        if (lastGroup != null && group != lastGroup) result += Separator.getInstance()
        lastGroup = group
        result += if (target == null) {
            DynamicCommandActions.actionFor(item.command) ?: SidecarCommandAction(info)
        } else {
            SidecarCommandAction(info, target)
        }
    }
    return result.toTypedArray()
}

private fun groupName(item: MenuItem): String = item.group?.substringBefore('@') ?: ""

private fun groupOrder(item: MenuItem): Int = item.group?.substringAfter('@', "")?.toIntOrNull() ?: Int.MAX_VALUE

/** Tab toolbar: `menus["view/title"]` for one view. */
class ViewTitleActionGroup(private val service: SidecarService, private val viewId: String) :
    ActionGroup(),
    DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val items = service.initializeResult?.menus?.viewTitle ?: return EMPTY_ARRAY
        return menuActions(service, items, whenContext(service, viewId, null), null)
    }
}

/** Tree context menu: `menus["view/item/context"]` for the selected item. */
class ItemContextActionGroup(
    private val service: SidecarService,
    private val viewId: String,
    private val selectedNode: () -> Any?,
) : ActionGroup(),
    DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val items = service.initializeResult?.menus?.viewItemContext ?: return EMPTY_ARRAY
        val node = JsonTreeModel.dataOf(selectedNode())?.node ?: return EMPTY_ARRAY
        val context = whenContext(service, viewId, node.contextValue)
        return menuActions(service, items, context, TreeItemRef(viewId, node.id))
    }
}
