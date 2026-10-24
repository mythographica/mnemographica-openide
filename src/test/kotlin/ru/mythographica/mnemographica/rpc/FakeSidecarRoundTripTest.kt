package ru.mythographica.mnemographica.rpc

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Wire-level check of Protocol.kt + SidecarEndpoint against src/test/resources/fake-sidecar.js over real lsp4j framing.
 * Skipped when no Node binary is found.
 */
class FakeSidecarRoundTripTest {
    /** Minimal sidecar -> host side: records what arrives. */
    class RecordingHost {
        val registered = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<String>()

        @JsonNotification("tree/registered")
        fun treeRegistered(params: TreeRegisteredParams) {
            registered += params.viewId
        }

        @JsonRequest("window/showMessage")
        fun showMessage(params: ShowMessageParams): CompletableFuture<String?> {
            messages += params.message
            return CompletableFuture.completedFuture(null)
        }
    }

    private lateinit var process: Process
    private lateinit var remote: SidecarEndpoint
    private val host = RecordingHost()

    @Before
    fun start() {
        val node = findNode()
        assumeTrue("node not found", node != null)
        val script = File("src/test/resources/fake-sidecar.js").absoluteFile
        process = ProcessBuilder(node!!.path, script.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val launcher = Launcher.Builder<SidecarEndpoint>()
            .setLocalService(host)
            .setRemoteInterface(SidecarEndpoint::class.java)
            .setInput(process.inputStream)
            .setOutput(process.outputStream)
            .configureGson { it.serializeNulls() }
            .create()
        launcher.startListening()
        remote = launcher.remoteProxy
    }

    @After
    fun stop() {
        if (::process.isInitialized) process.destroy()
    }

    @Test
    fun `initialize, trees, commands and navigation`() {
        val result = remote.initialize(InitializeParams(projectRoots = listOf("/tmp"))).get(10, TimeUnit.SECONDS)
        assertEquals(7, result.views.size)
        assertEquals(7, host.registered.size)
        assertTrue(result.commands.any { it.command == "mnemographica.showTypeGraph" && it.title == "Ψ 3D" })
        assertEquals(6, result.menus.viewTitle.size)
        assertEquals(9, result.menus.viewItemContext.size)
        assertTrue(result.configuration!!.toString().contains("mnemographica.strategyLogPort"))

        val roots = remote.getChildren(GetChildrenParams("mnemonicaTypes", null)).get(5, TimeUnit.SECONDS)!!
        assertEquals(listOf("Registry", "Main"), roots.map { it.label })
        assertEquals(TreeNode.EXPANDED, roots[0].collapsibleState)
        assertEquals(1, roots[1].collapsibleState)
        assertEquals("navigable", roots[0].contextValue)
        assertEquals("mnemographica.navigateToLocation", roots[0].command?.command)
        val children = remote.getChildren(GetChildrenParams("mnemonicaTypes", roots[0].id)).get(5, TimeUnit.SECONDS)!!
        assertEquals(listOf("Trie", "Usages"), children.map { it.label })
        assertEquals(roots[0].id, remote.getParent(GetParentParams("mnemonicaTypes", children[0].id)).get(5, TimeUnit.SECONDS))

        remote.executeCommand(ExecuteCommandParams("mnemographica.showOnGraph", emptyList(), TreeItemRef("mnemonicaTypes", roots[0].id)))
            .get(5, TimeUnit.SECONDS)
        assertTrue(host.messages.single().contains("mnemographica.showOnGraph on mnemonicaTypes:Registry"))

        val definition = remote.provideDefinition(DefinitionParams("/tmp/a.ts", Position(3, 4))).get(5, TimeUnit.SECONDS)!!
        assertEquals("/tmp/a.ts", definition.single().path)
        assertEquals(5, definition.single().range.end.character)
    }

    private fun findNode(): File? {
        val nvm = File(System.getProperty("user.home"), ".nvm/versions/node").listFiles()?.sortedDescending()
            ?.map { File(it, "bin/node") }.orEmpty()
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "node") }
        return (path + nvm + File("/opt/homebrew/bin/node") + File("/usr/local/bin/node")).firstOrNull { it.canExecute() }
    }
}
