package ru.mythographica.mnemographica.webview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Opens a [WebviewFileEditor] for every [WebviewVirtualFile]. */
class WebviewFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is WebviewVirtualFile

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        WebviewFileEditor(file as WebviewVirtualFile, WebviewPanelRegistry.getInstance(project).get(file.panelId))

    override fun getEditorTypeId(): String = "mnemographica-webview"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
