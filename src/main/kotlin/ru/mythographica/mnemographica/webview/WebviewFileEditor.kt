package ru.mythographica.mnemographica.webview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * The editor tab of a webview panel. The JCEF browser belongs to the [WebviewPanel] (it exists before the tab opens and
 * receives html and messages regardless of the tab), so disposing the editor does not dispose the page; the registry does
 * that when the tab closes.
 */
class WebviewFileEditor(private val file: WebviewVirtualFile, private val panel: WebviewPanel?) :
    UserDataHolderBase(),
    FileEditor {
    private val component: JComponent =
        panel?.browser?.component
            ?: JBLabel(panel?.unsupportedReason ?: "This Mnemonica panel is closed.", SwingConstants.CENTER)

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent = component

    override fun getName(): String = "Mnemonica Webview"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() = Unit
}
