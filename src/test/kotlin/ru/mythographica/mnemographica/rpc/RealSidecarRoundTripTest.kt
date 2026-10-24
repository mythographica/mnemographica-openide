package ru.mythographica.mnemographica.rpc

import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Integration check of the host's RPC layer (Protocol.kt, SidecarEndpoint, Gson with serializeNulls) against the
 * real sidecar bundle (`sidecar/dist/main.js`) running the unmodified extension over `mnemographica/test/fixtures-v2`.
 * Skipped when Node or the bundle is missing (`./gradlew buildSidecar` produces it).
 */
class RealSidecarRoundTripTest {
    class RecordingHost {
        val registered = CopyOnWriteArrayList<String>()
        val invalidated = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()

        @JsonNotification("tree/registered")
        fun treeRegistered(params: TreeRegisteredParams) {
            registered += params.viewId
        }

        @JsonNotification("tree/invalidate")
        fun treeInvalidate(params: TreeInvalidateParams) {
            invalidated += params.viewId
        }

        @JsonNotification("log")
        fun log(params: LogParams) {
            logs += "${params.level}: ${params.message}"
        }

        @JsonNotification("tree/setMeta")
        fun setMeta(params: TreeSetMetaParams) = Unit

        @JsonNotification("statusBar/update")
        fun statusBar(params: StatusBarUpdateParams) = Unit

        @JsonNotification("output/append")
        fun outputAppend(params: OutputAppendParams) = Unit

        @JsonNotification("fs/watchers")
        fun watchers(params: Any?) = Unit

        @JsonRequest("window/showMessage")
        fun showMessage(params: ShowMessageParams): CompletableFuture<String?> = CompletableFuture.completedFuture(null)
    }

    private lateinit var process: Process
    private lateinit var remote: SidecarEndpoint
    private val host = RecordingHost()
    private val dist = File("sidecar/dist").absoluteFile
    private val fixtures = File("mnemographica/test/fixtures-v2").absoluteFile

    @Before
    fun start() {
        val node = findNode()
        assumeTrue("node not found", node != null)
        assumeTrue("sidecar/dist/main.js not built", File(dist, "main.js").isFile)
        process = ProcessBuilder(node!!.path, File(dist, "main.js").path)
            .directory(dist)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
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
    fun `real extension answers initialize and serves all seven trees`() {
        val storage = Files.createTempDirectory("mnemo-test").toFile()
        val result = remote.initialize(
            InitializeParams(
                projectRoots = listOf(fixtures.path),
                extensionPath = dist.path,
                config = mapOf("mnemographica.strategyLogPort" to JsonPrimitive(9250)),
                ideVersion = "test",
                pluginVersion = "test",
                storagePath = storage.path,
            ),
        ).get(30, TimeUnit.SECONDS)
        remote.initialized()

        assertEquals(7, result.views.size)
        assertEquals(result.views.map { it.id }.toSet(), host.registered.toSet())
        assertTrue(result.commands.any { it.command == "mnemographica.showTypeGraph" })
        assertTrue(result.commands.any { it.internal == true })

        // `itemId: null` is serialized explicitly by the host and must mean "roots" to the sidecar.
        val types = remote.getChildren(GetChildrenParams("mnemonicaTypes", null)).get(10, TimeUnit.SECONDS)!!
        assertEquals(listOf("Definitions"), types.map { it.label })
        val definitions = remote.getChildren(GetChildrenParams("mnemonicaTypes", types[0].id)).get(10, TimeUnit.SECONDS)!!
        assertFalse("Definitions has children", definitions.isEmpty())
        assertTrue(definitions.any { it.contextValue == "navigable" })

        val gen = remote.getChildren(GetChildrenParams("mnemonicaGen", null)).get(10, TimeUnit.SECONDS)!!
        assertTrue(gen.first().label.startsWith("Roots"))
        for (view in result.views) {
            assertNotNull(view.id, remote.getChildren(GetChildrenParams(view.id, null)).get(10, TimeUnit.SECONDS))
        }

        // Explicit `context: null` and a full config snapshot are both accepted.
        remote.configChanged(ConfigChangedParams(mapOf("mnemographica.usagesPathFilter" to JsonPrimitive("spec"))))
        remote.executeCommand(ExecuteCommandParams("mnemographica.refreshTree", emptyList(), null)).get(10, TimeUnit.SECONDS)

        assertEquals("null", remote.shutdown().get(10, TimeUnit.SECONDS)?.toString() ?: "null")
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertTrue("no sidecar errors: ${host.logs}", host.logs.none { it.startsWith("error") })
    }

    private fun findNode(): File? {
        val nvm = File(System.getProperty("user.home"), ".nvm/versions/node").listFiles()?.sortedDescending()
            ?.map { File(it, "bin/node") }.orEmpty()
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "node") }
        return (path + nvm + File("/opt/homebrew/bin/node") + File("/usr/local/bin/node")).firstOrNull { it.canExecute() }
    }
}
