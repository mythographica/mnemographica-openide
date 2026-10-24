package ru.mythographica.mnemographica.rpc

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.intellij.ide.BrowserUtil
import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import ru.mythographica.mnemographica.navigation.LocationNavigator
import ru.mythographica.mnemographica.settings.MnemonicaConfigurable
import ru.mythographica.mnemographica.sidecar.SidecarService
import ru.mythographica.mnemographica.sidecar.UiDispatcher
import ru.mythographica.mnemographica.toolwindow.ViewTabRegistry
import ru.mythographica.mnemographica.ui.LoggerConsole
import ru.mythographica.mnemographica.ui.MnemonicaNotifications
import ru.mythographica.mnemographica.ui.MnemonicaStatusBarWidgetFactory
import ru.mythographica.mnemographica.ui.ProgressTasks
import ru.mythographica.mnemographica.webview.WebviewPanelRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.ListSelectionModel

/**
 * Sidecar -> host methods of PROTOCOL.md. lsp4j calls these on its reader thread, so nothing here blocks:
 * UI work is dispatched to the EDT and requests complete their future from there.
 */
class HostEndpoint(private val project: Project, private val service: SidecarService) {
    private val scope: CoroutineScope get() = service.scope
    private val progress = ProgressTasks(project) { id -> service.remote?.progressCancelled(ProgressCancelledParams(id)) }

    private fun <T> onEdt(block: suspend () -> T): CompletableFuture<T> =
        scope.future { withContext(Dispatchers.EDT) { block() } }

    private fun edt(block: () -> Unit) {
        scope.launch(UiDispatcher) { if (!project.isDisposed) block() }
    }

    private fun tabs() = ViewTabRegistry.getInstance(project)

    // ---------- log / messages ----------

    @JsonNotification("log")
    fun log(params: LogParams) {
        val text = "[sidecar] ${params.message}"
        when (params.level) {
            "debug" -> LOG.debug(text)
            "info" -> LOG.info(text)
            // LOG.error would raise an IDE error report against the plugin; sidecar errors are warnings here.
            else -> LOG.warn(text)
        }
    }

    @JsonRequest("window/showMessage")
    fun showMessage(params: ShowMessageParams): CompletableFuture<String?> {
        if (params.modal == true) {
            return onEdt {
                val buttons = (params.items.ifEmpty { listOf("OK") }).toTypedArray()
                val icon = when (params.type) {
                    "error" -> Messages.getErrorIcon()
                    "warning" -> Messages.getWarningIcon()
                    else -> Messages.getInformationIcon()
                }
                val index = Messages.showDialog(project, params.message, "Mnemonica", buttons, 0, icon)
                params.items.getOrNull(index)
            }
        }
        val answer = CompletableFuture<String?>()
        edt {
            val type = when (params.type) {
                "error" -> NotificationType.ERROR
                "warning" -> NotificationType.WARNING
                else -> NotificationType.INFORMATION
            }
            val actions = params.items.map { item ->
                NotificationAction.createSimpleExpiring(item) { answer.complete(item) }
            }
            MnemonicaNotifications.notify(project, params.message, type, actions) { answer.complete(null) }
            if (params.items.isEmpty()) answer.complete(null)
        }
        return answer
    }

    @JsonRequest("window/showQuickPick")
    fun showQuickPick(params: ShowQuickPickParams): CompletableFuture<List<Int>?> {
        val answer = CompletableFuture<List<Int>?>()
        edt {
            val labels = params.items.map { item ->
                listOfNotNull(item.label, item.description?.takeIf { it.isNotBlank() }).joinToString("  —  ")
            }
            val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(labels.indices.toList())
                .setRenderer(textListCellRenderer<Int> { labels[it] })
                .setTitle(params.placeHolder ?: "Mnemonica")
                .setNamerForFiltering { labels[it] }
                .addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        // On OK the chosen callback completes the answer; the later null is a no-op then.
                        if (event.isOk) edt { answer.complete(null) } else answer.complete(null)
                    }
                })
            if (params.canPickMany == true) {
                builder.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
                    .setItemsChosenCallback { answer.complete(it.toList().sorted()) }
            } else {
                builder.setItemChosenCallback { answer.complete(listOf(it)) }
            }
            builder.createPopup().showCenteredInCurrentWindow(project)
        }
        return answer
    }

    @JsonRequest("window/showInputBox")
    fun showInputBox(params: ShowInputBoxParams): CompletableFuture<String?> = onEdt {
        val prompt = params.prompt ?: params.placeHolder ?: ""
        if (params.password == true) {
            Messages.showPasswordDialog(project, prompt, "Mnemonica", null)
        } else {
            Messages.showInputDialog(project, prompt, "Mnemonica", null, params.value, null)
        }
    }

    @JsonRequest("window/showOpenDialog")
    fun showOpenDialog(params: ShowOpenDialogParams): CompletableFuture<List<String>?> = onEdt {
        val descriptor = FileChooserDescriptor(
            params.canSelectFiles,
            params.canSelectFolders,
            false,
            false,
            false,
            params.canSelectMany,
        ).withTitle(params.title ?: "Mnemonica")
        val toSelect = params.defaultPath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        FileChooser.chooseFiles(descriptor, project, toSelect).map { it.path }.ifEmpty { null }
    }

    @JsonRequest("window/showSaveDialog")
    fun showSaveDialog(params: ShowSaveDialogParams): CompletableFuture<String?> = onEdt {
        val extensions = params.filters?.values?.flatten()?.distinct().orEmpty().toTypedArray()
        val descriptor = FileSaverDescriptor(params.title ?: "Mnemonica", "")
        if (extensions.isNotEmpty()) {
            descriptor.withExtensionFilter(params.filters?.keys?.joinToString().orEmpty(), *extensions)
        }
        val default = params.defaultPath?.let(::File)
        val baseDir = default?.let { if (it.isDirectory) it else it.parentFile }
            ?.let { LocalFileSystem.getInstance().findFileByIoFile(it) }
        val name = default?.takeUnless { it.isDirectory }?.name
        FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(baseDir, name)?.file?.path
    }

    @JsonRequest("window/showTextDocument")
    fun showTextDocument(params: ShowTextDocumentParams): CompletableFuture<JsonElement?> {
        val answer = CompletableFuture<JsonElement?>()
        LocationNavigator.open(project, params.path, params.selection, params.preserveFocus != true) { opened ->
            if (opened) {
                answer.complete(JsonNull.INSTANCE)
            } else {
                answer.completeExceptionally(error(ResponseErrorCode.InvalidParams, "File not found: ${params.path}"))
            }
        }
        return answer
    }

    @JsonNotification("window/openExternal")
    fun openExternal(params: OpenExternalParams) {
        BrowserUtil.browse(params.url)
    }

    // ---------- progress / status bar / output ----------

    @JsonNotification("progress/begin")
    fun progressBegin(params: ProgressBeginParams) = edt { progress.begin(params) }

    @JsonNotification("progress/report")
    fun progressReport(params: ProgressReportParams) = progress.report(params)

    @JsonNotification("progress/end")
    fun progressEnd(params: ProgressEndParams) = progress.end(params.id)

    @JsonNotification("statusBar/update")
    fun statusBarUpdate(params: StatusBarUpdateParams) {
        service.statusItems[params.id] = params
        edt { MnemonicaStatusBarWidgetFactory.update(project) }
    }

    @JsonNotification("output/append")
    fun outputAppend(params: OutputAppendParams) = LoggerConsole.getInstance(project).append(params.channel, params.text)

    @JsonNotification("output/show")
    fun outputShow(params: OutputShowParams) =
        LoggerConsole.getInstance(project).show(params.channel, params.preserveFocus == true)

    // ---------- trees ----------

    @JsonNotification("tree/registered")
    fun treeRegistered(params: TreeRegisteredParams) {
        service.registeredViews.add(params.viewId)
        LOG.info("Mnemonica: tree/registered ${params.viewId}")
        edt { tabs().onRegistered(params.viewId) }
    }

    @JsonNotification("tree/invalidate")
    fun treeInvalidate(params: TreeInvalidateParams) = edt { tabs().get(params.viewId)?.invalidate(params.itemId) }

    @JsonNotification("tree/reveal")
    fun treeReveal(params: TreeRevealParams) = edt { tabs().get(params.viewId)?.reveal(params) }

    @JsonNotification("tree/setMeta")
    fun treeSetMeta(params: TreeSetMetaParams) = edt { tabs().setMeta(params) }

    @JsonNotification("view/focus")
    fun viewFocus(params: ViewFocusParams) = edt { tabs().focus(params.viewId) }

    // ---------- webviews ----------

    private fun webviews() = WebviewPanelRegistry.getInstance(project)

    @JsonRequest("webview/create")
    fun webviewCreate(params: WebviewCreateParams): CompletableFuture<JsonElement?> {
        // Answered once the panel exists, so the html and messages the sidecar sends next find it.
        val created = CompletableFuture<JsonElement?>()
        scope.launch(UiDispatcher) {
            try {
                if (!project.isDisposed) webviews().create(params)
                created.complete(JsonNull.INSTANCE)
            } catch (e: Throwable) {
                LOG.warn("Mnemonica: webview/create ${params.panelId} failed", e)
                created.completeExceptionally(error(ResponseErrorCode.InternalError, "webview/create failed: ${e.message}"))
            }
        }
        return created
    }

    @JsonNotification("webview/setHtml")
    fun webviewSetHtml(params: WebviewSetHtmlParams) = webviews().setHtml(params.panelId, params.html)

    @JsonNotification("webview/postMessage")
    fun webviewPostMessage(params: WebviewPostMessageParams) = webviews().postMessage(params.panelId, params.message)

    @JsonNotification("webview/reveal")
    fun webviewReveal(params: WebviewRevealParams) = edt { webviews().reveal(params.panelId, params.preserveFocus == true) }

    @JsonNotification("webview/setTitle")
    fun webviewSetTitle(params: WebviewSetTitleParams) = edt { webviews().setTitle(params.panelId, params.title) }

    @JsonNotification("webview/dispose")
    fun webviewDispose(params: WebviewDisposeParams) = edt { webviews().dispose(params.panelId) }

    // ---------- builtins / misc ----------

    @JsonRequest("commands/executeBuiltin")
    fun executeBuiltin(params: ExecuteBuiltinParams): CompletableFuture<JsonElement?> {
        val done = CompletableFuture.completedFuture<JsonElement?>(JsonNull.INSTANCE)
        val args = params.args
        return when (params.command) {
            "setContext" -> {
                val key = args.getOrNull(0)?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return failed(ResponseErrorCode.InvalidParams, "setContext needs a key")
                service.contextKeys[key] = args.getOrNull(1) ?: JsonNull.INSTANCE
                done
            }
            "vscode.open" -> {
                val path = pathArg(args.getOrNull(0)) ?: return failed(ResponseErrorCode.InvalidParams, "vscode.open needs a path")
                val range = args.getOrNull(1)?.let(::rangeArg)
                LocationNavigator.open(project, path, range, true)
                done
            }
            "revealInExplorer" -> {
                val path = pathArg(args.getOrNull(0)) ?: return failed(ResponseErrorCode.InvalidParams, "revealInExplorer needs a path")
                scope.launch(Dispatchers.IO) {
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return@launch
                    withContext(Dispatchers.EDT) {
                        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)?.activate {
                            ProjectView.getInstance(project).select(null, file, true)
                        }
                    }
                }
                done
            }
            "workbench.action.openSettings" -> {
                edt { ShowSettingsUtil.getInstance().showSettingsDialog(project, MnemonicaConfigurable::class.java) }
                done
            }
            "workbench.view.explorer" -> {
                edt { ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)?.activate(null) }
                done
            }
            else -> failed(ResponseErrorCode.MethodNotFound, "Unsupported builtin command: ${params.command}")
        }
    }

    @JsonNotification("fs/watchers")
    fun fsWatchers(params: FsWatchersParams) {
        LOG.debug("Mnemonica: sidecar watches ${params.globs}")
    }

    private fun pathArg(arg: JsonElement?): String? = when {
        arg == null || arg.isJsonNull -> null
        arg.isJsonPrimitive -> arg.asString.removePrefix("file://")
        arg.isJsonObject -> arg.asJsonObject.let { (it.get("fsPath") ?: it.get("path"))?.asString }
        else -> null
    }

    private fun rangeArg(arg: JsonElement): Range? {
        if (!arg.isJsonObject) return null
        val obj = arg.asJsonObject
        val rangeJson = obj.get("selection") ?: obj
        return runCatching { GSON.fromJson(rangeJson, Range::class.java) }.getOrNull()?.takeIf { rangeJson.asJsonObject.has("start") }
    }

    private fun <T> failed(code: ResponseErrorCode, message: String): CompletableFuture<T> =
        CompletableFuture.failedFuture(error(code, message))

    private fun error(code: ResponseErrorCode, message: String) = ResponseErrorException(ResponseError(code, message, null))

    companion object {
        private val LOG = logger<HostEndpoint>()
        private val GSON = Gson()
    }
}
