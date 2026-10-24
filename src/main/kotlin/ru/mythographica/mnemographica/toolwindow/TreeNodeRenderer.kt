package ru.mythographica.mnemographica.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree

/** Label, grey description and icon of a [NodeData]; the "Loading…" placeholder in grey. */
class TreeNodeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val data = JsonTreeModel.dataOf(value)
        if (data == null) {
            icon = AllIcons.Process.Step_1
            append(LoadingMarker.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return
        }
        val node = data.node
        icon = IconMapper.forTreeNode(node.iconId, node.iconPath, node.iconColor)
        append(node.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        node.description?.takeIf { it.isNotBlank() }?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        toolTipText = node.tooltip
    }
}
