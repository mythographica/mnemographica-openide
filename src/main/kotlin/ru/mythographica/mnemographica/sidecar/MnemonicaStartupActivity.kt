package ru.mythographica.mnemographica.sidecar

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the sidecar when a project opens. */
class MnemonicaStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        SidecarService.getInstance(project).start()
    }
}
