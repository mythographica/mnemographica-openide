package ru.mythographica.mnemographica.navigation

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mythographica.mnemographica.rpc.Location
import ru.mythographica.mnemographica.rpc.Position
import ru.mythographica.mnemographica.rpc.ReferencesParams
import ru.mythographica.mnemographica.sidecar.SidecarService
import java.io.File
import javax.swing.Icon

/** "Find Mnemonica References": `references/provide` at the caret, results in a popup list. */
class FindReferencesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && e.getData(CommonDataKeys.EDITOR) != null &&
            file != null && MnemonicaGotoDeclarationHandler.isSupported(file) &&
            SidecarService.getInstance(project).remote != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val offset = editor.caretModel.offset
        val line = editor.document.getLineNumber(offset)
        val params = ReferencesParams(file.path, Position(line, offset - editor.document.getLineStartOffset(line)), true)
        val service = SidecarService.getInstance(project)
        service.scope.launch {
            val locations = service.request("references/provide") { it.provideReferences(params) }.await().orEmpty()
            withContext(Dispatchers.EDT) { show(project, editor, locations) }
        }
    }

    private fun show(project: Project, editor: Editor, locations: List<Location>) {
        if (locations.isEmpty()) {
            JBPopupFactory.getInstance().createMessage("No Mnemonica references found")
                .showInBestPositionFor(editor)
            return
        }
        if (locations.size == 1) {
            LocationNavigator.open(project, locations[0].path, locations[0].range, true)
            return
        }
        val base = project.basePath
        val step = object : BaseListPopupStep<Location>("Mnemonica References (${locations.size})", locations) {
            override fun getTextFor(value: Location): String {
                val path = base?.let { File(value.path).relativeToOrNull(File(it))?.path } ?: value.path
                return "$path:${value.range.start.line + 1}:${value.range.start.character + 1}"
            }

            override fun getIconFor(value: Location): Icon = AllIcons.Nodes.Class

            override fun onChosen(selectedValue: Location, finalChoice: Boolean): PopupStep<*>? {
                LocationNavigator.open(project, selectedValue.path, selectedValue.range, true)
                return FINAL_CHOICE
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        if (editor.component.isShowing) {
            popup.showInBestPositionFor(editor)
        } else {
            WindowManager.getInstance().getFrame(project)?.let { popup.show(RelativePoint.getCenterOf(it.rootPane)) }
        }
    }
}
