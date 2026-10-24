package ru.mythographica.mnemographica.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ui.ClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mythographica.mnemographica.actions.ItemContextActionGroup
import ru.mythographica.mnemographica.actions.ViewTitleActionGroup
import ru.mythographica.mnemographica.rpc.ExpansionChangedParams
import ru.mythographica.mnemographica.rpc.GetParentParams
import ru.mythographica.mnemographica.rpc.SelectionChangedParams
import ru.mythographica.mnemographica.rpc.TreeNode
import ru.mythographica.mnemographica.rpc.TreeRevealParams
import ru.mythographica.mnemographica.rpc.TreeSetMetaParams
import ru.mythographica.mnemographica.rpc.ViewInfo
import ru.mythographica.mnemographica.rpc.VisibilityChangedParams
import ru.mythographica.mnemographica.sidecar.SidecarService
import ru.mythographica.mnemographica.sidecar.UiDispatcher
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** One sidecar tree view: toolbar from `view/title`, the lazy tree, context menu from `view/item/context`. EDT only. */
class ViewTab(private val service: SidecarService, val view: ViewInfo) : Disposable {
    val model = JsonTreeModel(service, view.id)
    val tree = Tree(model)
    val component = JPanel(BorderLayout())
    var content: Content? = null

    private val messageLabel = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
        isVisible = false
    }
    private var title: String = view.name
    private var description: String? = null
    private var badge: Int? = null

    /** Label paths of nodes to re-expand after an invalidate (ids change, labels usually do not). */
    private val pendingExpand = HashSet<List<String>>()

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = TreeNodeRenderer()
        tree.emptyText.text = LOADING
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.toggleClickCount = 0

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "MnemonicaViewTitle",
            ViewTitleActionGroup(service, view.id),
            true,
        )
        toolbar.targetComponent = tree
        val north = JPanel(BorderLayout())
        north.add(toolbar.component, BorderLayout.NORTH)
        north.add(messageLabel, BorderLayout.SOUTH)
        component.add(north, BorderLayout.NORTH)
        component.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)

        installListeners()
    }

    private fun installListeners() {
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                if (!model.isLoaded(node)) load(node)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = sendExpansion(event, true)

            override fun treeCollapsed(event: TreeExpansionEvent) = sendExpansion(event, false)
        })
        tree.addTreeSelectionListener {
            val ids = tree.selectionPaths.orEmpty().mapNotNull { JsonTreeModel.dataOf(it.lastPathComponent)?.node?.id }
            service.remote?.selectionChanged(SelectionChangedParams(view.id, ids))
        }
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                if (event.button != MouseEvent.BUTTON1 || clickCount != 1) return false
                val path = tree.getPathForLocation(event.x, event.y) ?: return false
                activate(path)
                return true
            }
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) tree.selectionPath?.let { activate(it) }
            }
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                    tree.getPathForLocation(e.x, e.y)?.let { tree.selectionPath = it }
                }
            }
        })
        PopupHandler.installPopupMenu(tree, ItemContextActionGroup(service, view.id) { tree.selectionPath?.lastPathComponent }, "MnemonicaViewItem")
    }

    /** Click / Enter: run the item's command; an item without one toggles like VS Code. */
    private fun activate(path: TreePath) {
        val data = JsonTreeModel.dataOf(path.lastPathComponent) ?: return
        val command = data.node.command
        when {
            command != null -> service.executeCommand(command.command, command.arguments.orEmpty())
            !data.node.isLeaf -> if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
        }
    }

    private fun sendExpansion(event: TreeExpansionEvent, expanded: Boolean) {
        val data = JsonTreeModel.dataOf(event.path.lastPathComponent) ?: return
        service.remote?.expansionChanged(ExpansionChangedParams(view.id, data.node.id, expanded))
    }

    /** `tree/registered` arrived (or was already known when the tab was created). */
    fun onRegistered() {
        tree.emptyText.text = "Nothing to show"
        if (!model.rootsLoaded) load(model.rootNode)
    }

    /** Sidecar stopped or crashed: the tree content is no longer backed by live ids. */
    fun markStale(reason: String) {
        model.clear()
        tree.emptyText.text = reason
    }

    fun setVisible(visible: Boolean) {
        service.remote?.visibilityChanged(VisibilityChangedParams(view.id, visible))
    }

    /** `tree/invalidate`: re-fetch the subtree (null = whole tree), keeping what was expanded. */
    fun invalidate(itemId: String?) {
        val node = if (itemId == null) model.rootNode else model.find(itemId) ?: return
        val wasExpanded = node === model.rootNode || tree.isExpanded(TreePath(node.path))
        rememberExpanded(node)
        model.resetChildren(node)
        if (wasExpanded) load(node, expandAfter = node !== model.rootNode)
    }

    fun setMeta(params: TreeSetMetaParams) {
        params.title?.let { title = it }
        description = params.description
        badge = params.badge?.value?.takeIf { it > 0 }
        messageLabel.text = params.message.orEmpty()
        messageLabel.isVisible = !params.message.isNullOrBlank()
        updateTabName()
    }

    private fun updateTabName() {
        val name = buildString {
            append(title)
            badge?.let { append(" ($it)") }
        }
        content?.displayName = name
        content?.description = description
    }

    /** `tree/reveal`: build the ancestor chain with `tree/getParent`, load and expand it, then select. */
    fun reveal(params: TreeRevealParams) {
        service.scope.launch {
            val chain = ArrayDeque<String>()
            var current: String? = params.itemId
            var guard = 0
            while (current != null && guard++ < MAX_DEPTH) {
                chain.addFirst(current)
                current = service.request("tree/getParent") { it.getParent(GetParentParams(view.id, current!!)) }.await()
            }
            if (withContext(UiDispatcher) { !model.rootsLoaded }) model.loadChildren(model.rootNode)
            for (ancestorId in chain.dropLast(1)) {
                val node = withContext(UiDispatcher) { model.find(ancestorId) } ?: return@launch
                if (!withContext(UiDispatcher) { model.isLoaded(node) }) model.loadChildren(node)
                withContext(UiDispatcher) { tree.expandPath(TreePath(node.path)) }
            }
            withContext(UiDispatcher) {
                val node = model.find(params.itemId) ?: return@withContext
                val path = TreePath(node.path)
                val expandLevels = params.expand?.let { if (it.asJsonPrimitive.isBoolean) (if (it.asBoolean) 1 else 0) else it.asInt } ?: 0
                if (expandLevels > 0) tree.expandPath(path)
                if (params.select) tree.selectionPath = path
                tree.scrollPathToVisible(path)
                if (params.focus) tree.requestFocusInWindow()
            }
        }
    }

    private fun load(node: DefaultMutableTreeNode, expandAfter: Boolean = false) {
        service.scope.launch {
            val children = model.loadChildren(node) ?: return@launch
            withContext(UiDispatcher) {
                if (expandAfter) tree.expandPath(TreePath(node.path))
                for (child in children) {
                    val data = JsonTreeModel.dataOf(child) ?: continue
                    val labels = labelPath(child)
                    if (data.node.collapsibleState == TreeNode.EXPANDED || pendingExpand.remove(labels)) {
                        tree.expandPath(TreePath(child.path))
                    }
                }
            }
        }
    }

    private fun rememberExpanded(node: DefaultMutableTreeNode) {
        val expanded = tree.getExpandedDescendants(TreePath(node.path)) ?: return
        for (path in expanded) {
            labelPath(path.lastPathComponent as DefaultMutableTreeNode).takeIf { it.isNotEmpty() }?.let(pendingExpand::add)
        }
    }

    private fun labelPath(node: DefaultMutableTreeNode): List<String> =
        node.path.mapNotNull { JsonTreeModel.dataOf(it)?.node?.label }

    override fun dispose() = Unit

    companion object {
        const val LOADING = "Loading…"
        private const val MAX_DEPTH = 64
    }
}
