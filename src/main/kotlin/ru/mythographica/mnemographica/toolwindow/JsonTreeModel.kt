package ru.mythographica.mnemographica.toolwindow

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import ru.mythographica.mnemographica.rpc.GetChildrenParams
import ru.mythographica.mnemographica.rpc.TreeNode
import ru.mythographica.mnemographica.sidecar.SidecarService
import ru.mythographica.mnemographica.sidecar.UiDispatcher
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** User object of a sidecar-backed node; [loaded] is false until `tree/getChildren` answered for it. */
class NodeData(val node: TreeNode, var loaded: Boolean = false) {
    override fun toString(): String = node.label
}

/** User object of the placeholder child shown while children load. */
object LoadingMarker {
    override fun toString(): String = "Loading…"
}

/**
 * Swing tree model over `tree/getChildren`: children are fetched lazily, a "Loading…" child stands in until they arrive.
 * All mutation happens on the EDT; requests run in the sidecar service's coroutine scope.
 */
class JsonTreeModel(private val service: SidecarService, val viewId: String) :
    DefaultTreeModel(DefaultMutableTreeNode(null)) {
    private val byId = HashMap<String, DefaultMutableTreeNode>()
    private val generations = HashMap<DefaultMutableTreeNode, Int>()

    val rootNode: DefaultMutableTreeNode get() = root as DefaultMutableTreeNode

    var rootsLoaded = false
        private set

    fun find(itemId: String): DefaultMutableTreeNode? = byId[itemId]

    fun isLoaded(node: DefaultMutableTreeNode): Boolean =
        if (node === rootNode) rootsLoaded else (node.userObject as? NodeData)?.loaded == true

    /** Drops every node (sidecar restart / stop); EDT. */
    fun clear() {
        byId.clear()
        generations.clear()
        rootNode.removeAllChildren()
        rootsLoaded = false
        reload()
    }

    /**
     * Fetches and installs the children of [parent] (the invisible root for the view roots).
     * Returns the new child nodes, or null when a newer load for the same parent superseded this one.
     */
    suspend fun loadChildren(parent: DefaultMutableTreeNode): List<DefaultMutableTreeNode>? {
        val generation = withContext(UiDispatcher) {
            (generations.getOrDefault(parent, 0) + 1).also { generations[parent] = it }
        }
        val itemId = (parent.userObject as? NodeData)?.node?.id
        val children = service.request("tree/getChildren") { it.getChildren(GetChildrenParams(viewId, itemId)) }.await()
            ?: emptyList()
        if (itemId == null) service.logRootsLoaded(viewId, children.size)
        return withContext(UiDispatcher) {
            if (generations[parent] != generation) return@withContext null
            if (parent !== rootNode && parent.parent == null) return@withContext null // removed meanwhile
            install(parent, children)
        }
    }

    /** Marks [parent] as not loaded and puts the "Loading…" placeholder back; EDT. */
    fun resetChildren(parent: DefaultMutableTreeNode) {
        unregisterChildren(parent)
        parent.removeAllChildren()
        if (parent === rootNode) {
            rootsLoaded = false
        } else {
            (parent.userObject as? NodeData)?.loaded = false
            parent.add(DefaultMutableTreeNode(LoadingMarker, false))
        }
        nodeStructureChanged(parent)
    }

    private fun install(parent: DefaultMutableTreeNode, children: List<TreeNode>): List<DefaultMutableTreeNode> {
        unregisterChildren(parent)
        parent.removeAllChildren()
        val created = children.map { child ->
            DefaultMutableTreeNode(NodeData(child), !child.isLeaf).also { treeNode ->
                if (!child.isLeaf) treeNode.add(DefaultMutableTreeNode(LoadingMarker, false))
                byId[child.id] = treeNode
                parent.add(treeNode)
            }
        }
        if (parent === rootNode) rootsLoaded = true else (parent.userObject as? NodeData)?.loaded = true
        nodeStructureChanged(parent)
        return created
    }

    private fun unregisterChildren(parent: DefaultMutableTreeNode) {
        val stack = ArrayDeque<DefaultMutableTreeNode>()
        for (i in 0 until parent.childCount) stack.add(parent.getChildAt(i) as DefaultMutableTreeNode)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            (node.userObject as? NodeData)?.let { byId.remove(it.node.id) }
            generations.remove(node)
            for (i in 0 until node.childCount) stack.add(node.getChildAt(i) as DefaultMutableTreeNode)
        }
    }

    companion object {
        fun dataOf(node: Any?): NodeData? = (node as? DefaultMutableTreeNode)?.userObject as? NodeData
    }
}
