package ru.mythographica.mnemographica.ui

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import ru.mythographica.mnemographica.rpc.ProgressBeginParams
import ru.mythographica.mnemographica.rpc.ProgressReportParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** `progress/begin|report|end` as background tasks in the status bar. */
class ProgressTasks(private val project: Project, private val onCancelled: (String) -> Unit) {
    private class Running(val done: CountDownLatch = CountDownLatch(1)) {
        @Volatile var message: String? = null
        @Volatile var fraction = 0.0
    }

    private val running = ConcurrentHashMap<String, Running>()

    fun begin(params: ProgressBeginParams) {
        val state = Running()
        running[params.id] = state
        val task = object : Task.Backgroundable(project, params.title ?: "Mnemonica", params.cancellable == true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    while (!state.done.await(100, TimeUnit.MILLISECONDS)) {
                        indicator.checkCanceled()
                        state.message?.let { indicator.text2 = it }
                        if (state.fraction > 0) {
                            indicator.isIndeterminate = false
                            indicator.fraction = state.fraction.coerceAtMost(1.0)
                        }
                    }
                } catch (e: ProcessCanceledException) {
                    running.remove(params.id)
                    onCancelled(params.id)
                    throw e
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    fun report(params: ProgressReportParams) {
        val state = running[params.id] ?: return
        params.message?.let { state.message = it }
        params.increment?.let { state.fraction += it / 100.0 }
    }

    fun end(id: String) {
        running.remove(id)?.done?.countDown()
    }
}
