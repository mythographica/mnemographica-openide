package ru.mythographica.mnemographica.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import ru.mythographica.mnemographica.rpc.Position
import ru.mythographica.mnemographica.rpc.Range

/** Opens a sidecar location (absolute path + zero-based range) in an editor. */
object LocationNavigator {
    /** Resolves the file off the EDT (VFS refresh), then opens it on the EDT. Calls [done] with success. */
    fun open(project: Project, path: String, range: Range?, focus: Boolean, done: (Boolean) -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            ApplicationManager.getApplication().invokeLater({
                if (file == null) {
                    done(false)
                } else {
                    openNow(project, file, range, focus)
                    done(true)
                }
            }, project.disposed)
        }
    }

    /** EDT. */
    fun openNow(project: Project, file: VirtualFile, range: Range?, focus: Boolean) {
        val start = range?.start ?: Position()
        val descriptor = OpenFileDescriptor(project, file, start.line, start.character)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, focus)
        if (editor == null) {
            descriptor.navigate(focus)
            return
        }
        if (range != null && range.end != range.start) {
            val doc = editor.document
            fun offset(p: Position): Int {
                val line = p.line.coerceIn(0, maxOf(0, doc.lineCount - 1))
                if (doc.lineCount == 0) return 0
                return (doc.getLineStartOffset(line) + p.character).coerceAtMost(doc.getLineEndOffset(line))
            }
            editor.selectionModel.setSelection(offset(range.start), offset(range.end))
        }
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }
}
