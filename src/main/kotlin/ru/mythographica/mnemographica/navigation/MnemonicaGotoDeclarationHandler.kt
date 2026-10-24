package ru.mythographica.mnemographica.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import ru.mythographica.mnemographica.rpc.DefinitionParams
import ru.mythographica.mnemographica.rpc.Location
import ru.mythographica.mnemographica.rpc.Position
import ru.mythographica.mnemographica.sidecar.SidecarService
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/** Ctrl/Cmd+Click in .ts/.tsx/.js/.mjs files asks the extension's DefinitionProvider (`definition/provide`, 3 s). */
class MnemonicaGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val project = editor.project ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        if (!isSupported(file)) return null
        // Never wait for the sidecar on the EDT; the platform calls this in a background read action.
        if (ApplicationManager.getApplication().isDispatchThread) return null
        val service = SidecarService.getInstance(project)
        if (service.remote == null) return null

        val position = positionOf(editor, offset)
        val future = service.request("definition/provide") { it.provideDefinition(DefinitionParams(file.path, position)) }
            .orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val locations = try {
            ProgressIndicatorUtils.awaitWithCheckCanceled(future)
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            LOG.info("Mnemonica: definition/provide gave no answer: ${e.message}")
            null
        } ?: return null
        val targets = locations.mapNotNull { target(project, it) }
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun positionOf(editor: Editor, offset: Int): Position {
        val doc = editor.document
        val line = doc.getLineNumber(offset)
        return Position(line, offset - doc.getLineStartOffset(line))
    }

    private fun target(project: Project, location: Location): PsiElement? {
        val vf = LocalFileSystem.getInstance().findFileByPath(location.path) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        val start = location.range.start
        val offset = doc?.takeIf { start.line < it.lineCount }?.let { it.getLineStartOffset(start.line) + start.character }
            ?.coerceAtMost(doc.textLength) ?: 0
        return LocationTarget(psiFile, location, offset)
    }

    companion object {
        private val LOG = logger<MnemonicaGotoDeclarationHandler>()
        private const val TIMEOUT_MS = 3_000L
        private val EXTENSIONS = setOf("ts", "tsx", "js", "mjs")

        fun isSupported(file: VirtualFile): Boolean = file.isInLocalFileSystem && file.extension?.lowercase() in EXTENSIONS
    }
}

/** A navigation target at a sidecar [Location]; PSI-less languages (TextMate) included. */
class LocationTarget(private val file: PsiFile, val location: Location, private val offset: Int) : FakePsiElement() {
    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getTextOffset(): Int = offset

    override fun getName(): String = "${file.name}:${location.range.start.line + 1}"

    override fun getPresentableText(): String = name

    override fun getLocationString(): String = location.path

    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Class

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true

    override fun navigate(requestFocus: Boolean) {
        LocationNavigator.openNow(file.project, file.virtualFile, location.range, requestFocus)
    }
}
