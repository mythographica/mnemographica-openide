package ru.mythographica.mnemographica.sidecar

import com.google.gson.JsonElement
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.ui.JBColor
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import ru.mythographica.mnemographica.actions.DynamicCommandActions
import ru.mythographica.mnemographica.rpc.ActiveEditorParams
import ru.mythographica.mnemographica.rpc.ConfigChangedParams
import ru.mythographica.mnemographica.rpc.ExecuteCommandParams
import ru.mythographica.mnemographica.rpc.FoldersChangedParams
import ru.mythographica.mnemographica.rpc.HostEndpoint
import ru.mythographica.mnemographica.rpc.InitializeParams
import ru.mythographica.mnemographica.rpc.InitializeResult
import ru.mythographica.mnemographica.rpc.Position
import ru.mythographica.mnemographica.rpc.Range
import ru.mythographica.mnemographica.rpc.SidecarEndpoint
import ru.mythographica.mnemographica.rpc.StatusBarUpdateParams
import ru.mythographica.mnemographica.rpc.TreeItemRef
import ru.mythographica.mnemographica.settings.ConfigSchema
import ru.mythographica.mnemographica.settings.MnemonicaSettings
import ru.mythographica.mnemographica.ui.LoggerConsole
import ru.mythographica.mnemographica.ui.MnemonicaNotifications
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Per-project owner of the Node sidecar: start, initialize, restart, shutdown, and shared host-side state. */
@Service(Service.Level.PROJECT)
class SidecarService(val project: Project, val scope: CoroutineScope) : Disposable {
    enum class State { STOPPED, STARTING, RUNNING, NO_NODE, FAILED, CRASHED }

    fun interface Listener {
        fun stateChanged(state: State)
    }

    @Volatile
    var state: State = State.STOPPED
        private set

    /** Human-readable reason for NO_NODE / FAILED / CRASHED. */
    @Volatile
    var problem: String? = null
        private set

    @Volatile
    var initializeResult: InitializeResult? = null
        private set

    @Volatile
    var configSchema: ConfigSchema? = null
        private set

    /** Keys stored by `setContext`, read by `when` clauses. */
    val contextKeys = ConcurrentHashMap<String, JsonElement>()

    /** Views that sent `tree/registered`. */
    val registeredViews: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val statusItems = ConcurrentHashMap<String, StatusBarUpdateParams>()

    @Volatile
    private var process: SidecarProcess? = null

    @Volatile
    private var rpc: RpcClient? = null

    private val reportedFailures: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val remote: SidecarEndpoint?
        get() = rpc?.remote?.takeIf { state == State.RUNNING }

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                if (state != State.RUNNING) return
                scope.launch { remote?.foldersChanged(FoldersChangedParams(projectRoots())) }
            }
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = sendActiveEditor()
        })
    }

    fun start() {
        synchronized(this) {
            if (state == State.STARTING || state == State.RUNNING) return
            setState(State.STARTING, null)
        }
        scope.launch(Dispatchers.IO) {
            try {
                doStart()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                LOG.warn("Mnemonica: sidecar failed to start", e)
                teardown()
                setState(State.FAILED, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun stop() {
        val client = rpc
        val proc = process
        rpc = null
        process = null
        registeredViews.clear()
        setState(State.STOPPED, null)
        if (client == null || proc == null) return
        // Ask politely, then kill; never wait on the calling thread.
        client.remote.shutdown()
            .orTimeout(3, TimeUnit.SECONDS)
            .whenComplete { _, _ ->
                proc.process.waitFor(2, TimeUnit.SECONDS)
                proc.destroy()
                client.close()
            }
    }

    private suspend fun doStart() {
        val settings = MnemonicaSettings.getInstance(project)
        val node = NodeLocator.locate(settings.nodePath)
        if (node == null) {
            setState(State.NO_NODE, "Node.js ${NodeLocator.MIN_MAJOR} or newer was not found. Set its path in Settings | Tools | Mnemonica.")
            return
        }
        val main = sidecarMain()
        if (!main.isFile) {
            setState(State.FAILED, "Sidecar script not found: ${main.path}")
            return
        }
        val console = LoggerConsole.getInstance(project)
        val proc = SidecarProcess(node, main, main.parentFile) { line -> console.appendSidecar(line) }
        val client = RpcClient(proc, HostEndpoint(project, this))
        process = proc
        rpc = client
        proc.process.onExit().thenAccept { onProcessExit(proc, it.exitValue()) }

        val params = InitializeParams(
            projectRoots = projectRoots(),
            extensionPath = main.parentFile.path,
            config = settings.snapshot(configSchema),
            ideVersion = ApplicationInfo.getInstance().let { "${it.fullApplicationName} (${it.build.asStringWithoutProductCode()})" },
            pluginVersion = pluginVersion() ?: "dev",
            storagePath = storageDir().path,
            theme = if (JBColor.isBright()) "light" else "dark",
        )
        val result = client.remote.initialize(params).orTimeout(INITIALIZE_TIMEOUT_S, TimeUnit.SECONDS).await()
        if (rpc !== client) return
        initializeResult = result
        configSchema = ConfigSchema.parse(result.configuration)
        client.remote.initialized()
        // InitializeParams.config only knew stored values; now the schema defaults are known too.
        client.remote.configChanged(ConfigChangedParams(settings.snapshot(configSchema)))
        LOG.info(
            "Mnemonica sidecar initialized: node ${node.version}, ${result.commands.size} commands, " +
                "${result.views.size} views [${result.views.joinToString { it.id }}]",
        )
        withContext(UiDispatcher) {
            DynamicCommandActions.register(result)
        }
        setState(State.RUNNING, null)
        sendActiveEditor()
    }

    private fun onProcessExit(proc: SidecarProcess, exitCode: Int) {
        if (process !== proc) return
        LOG.info("Mnemonica: sidecar exited with code $exitCode")
        teardown()
        setState(State.CRASHED, "Mnemonica sidecar stopped (exit $exitCode)")
        if (project.isDisposed) return
        MnemonicaNotifications.notify(
            project,
            "Mnemonica sidecar stopped (exit $exitCode)",
            NotificationType.ERROR,
            listOf(NotificationAction.createSimpleExpiring("Restart") { restart() }),
        )
    }

    private fun teardown() {
        rpc?.close()
        process?.destroy()
        rpc = null
        process = null
        registeredViews.clear()
    }

    private fun setState(newState: State, reason: String?) {
        state = newState
        problem = reason
        if (reason != null) LOG.info("Mnemonica: sidecar $newState: $reason")
        scope.launch(UiDispatcher) {
            if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).stateChanged(newState)
        }
    }

    /**
     * Wraps a host -> sidecar request: failures are logged, reported in at most one balloon per method,
     * and turned into `null`.
     */
    fun <T> request(method: String, call: (SidecarEndpoint) -> CompletableFuture<T>): CompletableFuture<T?> {
        val endpoint = remote ?: return CompletableFuture.completedFuture(null)
        return call(endpoint).handle { value, error ->
            if (error != null) {
                reportFailure(method, error)
                null
            } else {
                value
            }
        }
    }

    fun reportFailure(method: String, error: Throwable) {
        val cause = (error as? java.util.concurrent.CompletionException)?.cause ?: error
        val text = (cause as? ResponseErrorException)?.responseError?.message ?: cause.message ?: cause.javaClass.simpleName
        LOG.warn("Mnemonica: $method failed: $text")
        if (reportedFailures.add(method)) {
            MnemonicaNotifications.notify(project, "Mnemonica: $method failed: $text", NotificationType.WARNING)
        }
    }

    /** Runs a sidecar command (`command/execute`) without blocking the caller. */
    fun executeCommand(command: String, args: List<JsonElement> = emptyList(), context: TreeItemRef? = null) {
        scope.launch {
            request("command/execute") { it.executeCommand(ExecuteCommandParams(command, args, context)) }.await()
        }
    }

    fun logRootsLoaded(viewId: String, count: Int) {
        LOG.info("Mnemonica: tree/getChildren($viewId, roots) answered with $count nodes")
    }

    fun pushConfig() {
        remote?.configChanged(ConfigChangedParams(MnemonicaSettings.getInstance(project).snapshot(configSchema)))
    }

    private fun sendActiveEditor() {
        if (state != State.RUNNING) return
        scope.launch(Dispatchers.EDT) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val path = editor?.virtualFile?.takeIf { it.isInLocalFileSystem }?.path
            val selection = editor?.let {
                val model = it.selectionModel
                val doc = it.document
                fun pos(offset: Int): Position {
                    val line = doc.getLineNumber(offset)
                    return Position(line, offset - doc.getLineStartOffset(line))
                }
                Range(pos(model.selectionStart), pos(model.selectionEnd))
            }
            remote?.activeEditorChanged(ActiveEditorParams(path, selection))
        }
    }

    private suspend fun projectRoots(): List<String> {
        val roots = readAction { ProjectRootManager.getInstance(project).contentRoots.map { it.path } }
        return roots.ifEmpty { listOfNotNull(project.basePath) }
    }

    private fun storageDir(): File =
        PathManager.getSystemDir().resolve("mnemographica").resolve(project.locationHash).toFile().apply { mkdirs() }

    override fun dispose() {
        stop()
    }

    companion object {
        private val LOG = logger<SidecarService>()
        private const val INITIALIZE_TIMEOUT_S = 120L
        const val PLUGIN_ID = "ru.mythographica.mnemographica"
        const val SIDECAR_MAIN_PROPERTY = "mnemographica.sidecar.main"

        @Topic.ProjectLevel
        val TOPIC = Topic.create("Mnemonica sidecar state", Listener::class.java)

        fun getInstance(project: Project): SidecarService = project.getService(SidecarService::class.java)

        /** `<version>` of the patched META-INF/plugin.xml (the plugin descriptor lookups are internal API). */
        fun pluginVersion(): String? = SidecarService::class.java.getResource("/META-INF/plugin.xml")
            ?.readText()
            ?.let { Regex("<version>([^<]+)</version>").find(it)?.groupValues?.get(1)?.trim() }

        /** `-Dmnemographica.sidecar.main=...` or `<plugin dir>/sidecar/main.js` (the plugin jar lives in `<plugin dir>/lib`). */
        fun sidecarMain(): File {
            System.getProperty(SIDECAR_MAIN_PROPERTY)?.takeIf { it.isNotBlank() }?.let { return File(it).absoluteFile }
            val pluginDir = File(PathManager.getJarPathForClass(SidecarService::class.java) ?: ".").absoluteFile.parentFile?.parentFile
            return File(pluginDir ?: File("."), "sidecar/main.js")
        }
    }
}
