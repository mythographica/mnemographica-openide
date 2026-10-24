package ru.mythographica.mnemographica.webview

import com.intellij.testFramework.LightVirtualFile

/** The editor-tab identity of a sidecar webview panel; the tab shows [title], which `webview/setTitle` changes. */
class WebviewVirtualFile(val panelId: String, title: String) : LightVirtualFile(title) {
    @Volatile
    var title: String = title

    init {
        isWritable = false
    }

    override fun getPresentableName(): String = title
}
