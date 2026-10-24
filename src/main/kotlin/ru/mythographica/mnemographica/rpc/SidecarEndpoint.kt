package ru.mythographica.mnemographica.rpc

import com.google.gson.JsonElement
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

/** Host -> sidecar methods of PROTOCOL.md; lsp4j generates the proxy. */
interface SidecarEndpoint {
    @JsonRequest("initialize")
    fun initialize(params: InitializeParams): CompletableFuture<InitializeResult>

    @JsonNotification("initialized")
    fun initialized()

    @JsonRequest("shutdown")
    fun shutdown(): CompletableFuture<JsonElement?>

    @JsonRequest("tree/getChildren")
    fun getChildren(params: GetChildrenParams): CompletableFuture<List<TreeNode>?>

    @JsonRequest("tree/getParent")
    fun getParent(params: GetParentParams): CompletableFuture<String?>

    @JsonNotification("tree/selectionChanged")
    fun selectionChanged(params: SelectionChangedParams)

    @JsonNotification("tree/visibilityChanged")
    fun visibilityChanged(params: VisibilityChangedParams)

    @JsonNotification("tree/expansionChanged")
    fun expansionChanged(params: ExpansionChangedParams)

    @JsonRequest("command/execute")
    fun executeCommand(params: ExecuteCommandParams): CompletableFuture<JsonElement?>

    @JsonNotification("webview/message")
    fun webviewMessage(params: WebviewMessageParams)

    @JsonNotification("webview/visibilityChanged")
    fun webviewVisibilityChanged(params: WebviewVisibilityParams)

    @JsonNotification("webview/disposed")
    fun webviewDisposed(params: WebviewDisposedParams)

    @JsonRequest("definition/provide")
    fun provideDefinition(params: DefinitionParams): CompletableFuture<List<Location>?>

    @JsonRequest("references/provide")
    fun provideReferences(params: ReferencesParams): CompletableFuture<List<Location>?>

    @JsonNotification("config/changed")
    fun configChanged(params: ConfigChangedParams)

    @JsonNotification("workspace/foldersChanged")
    fun foldersChanged(params: FoldersChangedParams)

    @JsonNotification("editor/activeChanged")
    fun activeEditorChanged(params: ActiveEditorParams)

    @JsonNotification("uri/handle")
    fun handleUri(params: UriHandleParams)

    @JsonNotification("progress/cancelled")
    fun progressCancelled(params: ProgressCancelledParams)
}
