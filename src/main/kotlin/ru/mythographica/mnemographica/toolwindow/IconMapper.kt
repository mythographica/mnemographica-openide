package ru.mythographica.mnemographica.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import java.awt.Color
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/** VS Code codicon ids (`refresh`, `$(refresh)`) and ThemeColor ids -> platform icons and colors. */
object IconMapper {
    val FALLBACK: Icon = AllIcons.Nodes.Tag

    private val codicons: Map<String, Icon> = mapOf(
        "refresh" to AllIcons.Actions.Refresh,
        "list-tree" to AllIcons.Actions.ShowAsTree,
        "server-environment" to AllIcons.Nodes.Deploy,
        "server-process" to AllIcons.Nodes.Console,
        "plug" to AllIcons.Nodes.Plugin,
        "settings-gear" to AllIcons.General.Settings,
        "gear" to AllIcons.General.Settings,
        "output" to AllIcons.Debugger.Console,
        "go-to-file" to AllIcons.Actions.EditSource,
        "file-code" to AllIcons.Actions.EditSource,
        "references" to AllIcons.Actions.Find,
        "search" to AllIcons.Actions.Search,
        "folder" to AllIcons.Nodes.Folder,
        "folder-opened" to AllIcons.Actions.MenuOpen,
        "symbol-class" to AllIcons.Nodes.Class,
        "symbol-method" to AllIcons.Nodes.Method,
        "symbol-function" to AllIcons.Nodes.Function,
        "symbol-field" to AllIcons.Nodes.Field,
        "symbol-property" to AllIcons.Nodes.Property,
        "symbol-misc" to AllIcons.Nodes.Tag,
        "debug-alt" to AllIcons.Actions.StartDebugger,
        "clear-all" to AllIcons.Actions.GC,
        "graph" to AllIcons.FileTypes.Diagram,
        "run" to AllIcons.Actions.Execute,
        "play" to AllIcons.Actions.Execute,
        "link-external" to AllIcons.Ide.External_link_arrow,
        "info" to AllIcons.General.Information,
        "warning" to AllIcons.General.Warning,
        "error" to AllIcons.General.Error,
        "question" to AllIcons.General.QuestionDialog,
        "pulse" to AllIcons.Actions.Profile,
        "broadcast" to AllIcons.General.Web,
        "circle-outline" to AllIcons.Nodes.EmptyNode,
        "close" to AllIcons.Actions.Close,
        "add" to AllIcons.General.Add,
        "trash" to AllIcons.Actions.GC,
        "file" to AllIcons.FileTypes.Any_type,
    )

    private val themeColors: Map<String, Color> = mapOf(
        "errorForeground" to JBColor.RED,
        "list.errorForeground" to JBColor.RED,
        "list.warningForeground" to JBColor.ORANGE,
        "charts.red" to JBColor.RED,
        "charts.orange" to JBColor.ORANGE,
        "charts.yellow" to JBColor.YELLOW,
        "charts.green" to JBColor.GREEN,
        "charts.blue" to JBColor.BLUE,
        "charts.purple" to JBColor.MAGENTA,
        "charts.foreground" to JBColor.foreground(),
    )

    private val fileIcons = ConcurrentHashMap<String, Icon>()

    /** Normalises `$(name)` and `name~spin` to `name`. */
    fun codiconId(raw: String): String = raw.trim().removePrefix("$(").removeSuffix(")").substringBefore('~')

    /** The mapped icon, or null when the id is unknown. */
    fun codicon(raw: String?): Icon? = raw?.let { codicons[codiconId(it)] }

    /** The mapped icon, or [FALLBACK] when the id is unknown. */
    fun codiconOrFallback(raw: String?): Icon = codicon(raw) ?: FALLBACK

    fun themeColor(id: String?): Color? = id?.let { themeColors[it] }

    /** TreeNode icon: `iconPath` wins over `iconId`; `iconColor` tints a mapped codicon. */
    fun forTreeNode(iconId: String?, iconPath: String?, iconColor: String?): Icon? {
        iconPath?.let { path -> fileIcon(path)?.let { return it } }
        if (iconId == null) return null
        val icon = codiconOrFallback(iconId)
        val color = themeColor(iconColor) ?: return icon
        return IconUtil.colorize(icon, color)
    }

    private fun fileIcon(path: String): Icon? {
        fileIcons[path]?.let { return it }
        val file = File(path)
        if (!file.isFile) return null
        val icon = IconLoader.findIcon(file.toURI().toURL()) ?: return null
        fileIcons[path] = icon
        return icon
    }
}
