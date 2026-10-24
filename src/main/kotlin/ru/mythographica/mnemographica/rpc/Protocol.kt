package ru.mythographica.mnemographica.rpc

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

// Mirrors sidecar/src/protocol.ts (see docs/PROTOCOL.md) field by field.
// Every property has a default so Gson can instantiate the classes through the no-arg constructor.

// ---------- shared ----------

data class Position(val line: Int = 0, val character: Int = 0)

data class Range(val start: Position = Position(), val end: Position = Position())

data class Location(val path: String = "", val range: Range = Range())

// ---------- lifecycle ----------

data class InitializeParams(
    val projectRoots: List<String> = emptyList(),
    val extensionPath: String = "",
    val config: Map<String, JsonElement> = emptyMap(),
    val ideVersion: String = "",
    val pluginVersion: String = "",
    val storagePath: String = "",
    val theme: String = "dark",
)

data class InitializeResult(
    val commands: List<CommandInfo> = emptyList(),
    val views: List<ViewInfo> = emptyList(),
    val menus: MenuContribution = MenuContribution(),
    /** contributes.configuration.properties (`mnemographica.<name>` -> property); `{ title, properties }` is accepted too. */
    val configuration: JsonObject? = null,
)

data class CommandInfo(
    val command: String = "",
    val title: String = "",
    val category: String? = null,
    val icon: String? = null,
    /** Registered by the extension but absent from contributes.commands; never a user-facing action. */
    val internal: Boolean? = null,
)

data class ViewInfo(
    val id: String = "",
    val name: String = "",
    val containerId: String = "",
    val containerTitle: String = "",
)

data class MenuItem(
    val command: String = "",
    val `when`: String? = null,
    val group: String? = null,
)

data class MenuContribution(
    @field:SerializedName("view/title")
    val viewTitle: List<MenuItem> = emptyList(),
    @field:SerializedName("view/item/context")
    val viewItemContext: List<MenuItem> = emptyList(),
    val commandPalette: List<MenuItem> = emptyList(),
)

// ---------- host -> sidecar ----------

data class GetChildrenParams(val viewId: String = "", val itemId: String? = null)

data class GetParentParams(val viewId: String = "", val itemId: String = "")

data class SelectionChangedParams(val viewId: String = "", val itemIds: List<String> = emptyList())

data class VisibilityChangedParams(val viewId: String = "", val visible: Boolean = false)

data class ExpansionChangedParams(val viewId: String = "", val itemId: String = "", val expanded: Boolean = false)

/** The tree element a `view/item/context` command was invoked on (see the protocol change request in the WP1 report). */
data class TreeItemRef(val viewId: String = "", val itemId: String = "")

data class ExecuteCommandParams(
    val command: String = "",
    val args: List<JsonElement> = emptyList(),
    val context: TreeItemRef? = null,
)

data class WebviewMessageParams(val panelId: String = "", val message: JsonElement? = null)

data class WebviewVisibilityParams(val panelId: String = "", val visible: Boolean = false, val active: Boolean = false)

data class WebviewDisposedParams(val panelId: String = "")

data class DefinitionParams(val path: String = "", val position: Position = Position())

data class ReferencesParams(
    val path: String = "",
    val position: Position = Position(),
    val includeDeclaration: Boolean = true,
)

data class ConfigChangedParams(val config: Map<String, JsonElement> = emptyMap())

data class FoldersChangedParams(val projectRoots: List<String> = emptyList())

data class ActiveEditorParams(val path: String? = null, val selection: Range? = null)

data class UriHandleParams(val uri: String = "")

data class ProgressCancelledParams(val id: String = "")

data class TreeNodeCommand(val command: String = "", val arguments: List<JsonElement>? = null)

data class TreeNode(
    val id: String = "",
    val label: String = "",
    val description: String? = null,
    val tooltip: String? = null,
    val iconId: String? = null,
    val iconPath: String? = null,
    val iconColor: String? = null,
    val collapsibleState: Int = 0,
    val contextValue: String? = null,
    val command: TreeNodeCommand? = null,
    val resourcePath: String? = null,
) {
    val isLeaf: Boolean get() = collapsibleState == 0

    companion object {
        const val EXPANDED = 2
    }
}

// ---------- sidecar -> host ----------

data class LogParams(val level: String = "info", val message: String = "")

data class ShowMessageParams(
    val type: String = "info",
    val message: String = "",
    val items: List<String> = emptyList(),
    val modal: Boolean? = null,
)

data class QuickPickItem(val label: String = "", val description: String? = null, val detail: String? = null)

data class ShowQuickPickParams(
    val items: List<QuickPickItem> = emptyList(),
    val placeHolder: String? = null,
    val canPickMany: Boolean? = null,
)

data class ShowInputBoxParams(
    val prompt: String? = null,
    val value: String? = null,
    val placeHolder: String? = null,
    val password: Boolean? = null,
)

data class ShowOpenDialogParams(
    val canSelectFiles: Boolean = true,
    val canSelectFolders: Boolean = false,
    val canSelectMany: Boolean = false,
    val title: String? = null,
    val defaultPath: String? = null,
    val openLabel: String? = null,
)

data class ShowSaveDialogParams(
    val defaultPath: String? = null,
    val title: String? = null,
    val filters: Map<String, List<String>>? = null,
)

data class ShowTextDocumentParams(
    val path: String = "",
    val selection: Range? = null,
    val preserveFocus: Boolean? = null,
    val viewColumn: Int? = null,
)

data class OpenExternalParams(val url: String = "")

data class ProgressBeginParams(val id: String = "", val title: String? = null, val cancellable: Boolean? = null)

data class ProgressReportParams(val id: String = "", val message: String? = null, val increment: Double? = null)

data class ProgressEndParams(val id: String = "")

data class StatusBarUpdateParams(
    val id: String = "",
    val text: String = "",
    val tooltip: String? = null,
    val command: String? = null,
    val visible: Boolean = false,
    val alignment: String = "left",
    val priority: Double? = null,
)

data class OutputAppendParams(val channel: String = "", val text: String = "")

data class OutputShowParams(val channel: String = "", val preserveFocus: Boolean? = null)

data class TreeRegisteredParams(
    val viewId: String = "",
    val showCollapseAll: Boolean? = null,
    val canSelectMany: Boolean? = null,
)

data class TreeInvalidateParams(val viewId: String = "", val itemId: String? = null)

data class TreeRevealParams(
    val viewId: String = "",
    val itemId: String = "",
    val select: Boolean = true,
    val focus: Boolean = false,
    /** `boolean | number` on the wire. */
    val expand: JsonElement? = null,
)

data class Badge(val value: Int = 0, val tooltip: String? = null)

data class TreeSetMetaParams(
    val viewId: String = "",
    val title: String? = null,
    val description: String? = null,
    val message: String? = null,
    val badge: Badge? = null,
)

data class ViewFocusParams(val viewId: String = "")

data class WebviewCreateParams(
    val panelId: String = "",
    val viewType: String = "",
    val title: String = "",
    val viewColumn: Int? = null,
    val enableScripts: Boolean = false,
    val retainContextWhenHidden: Boolean = false,
    val localResourceRoots: List<String> = emptyList(),
)

data class WebviewSetHtmlParams(val panelId: String = "", val html: String = "")

data class WebviewPostMessageParams(val panelId: String = "", val message: JsonElement? = null)

data class WebviewRevealParams(val panelId: String = "", val viewColumn: Int? = null, val preserveFocus: Boolean? = null)

data class WebviewSetTitleParams(val panelId: String = "", val title: String = "")

data class WebviewDisposeParams(val panelId: String = "")

data class ExecuteBuiltinParams(val command: String = "", val args: List<JsonElement> = emptyList())

data class FsWatchersParams(val globs: List<String> = emptyList())
