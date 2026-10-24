package ru.mythographica.mnemographica.sidecar

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import java.io.File
import kotlin.concurrent.thread

/**
 * The Node child process: stdin/stdout carry JSON-RPC, stderr is human-readable log lines.
 * `MNEMO_SIDECAR_INSPECT=<port>` (env or system property) starts Node with `--inspect`.
 */
class SidecarProcess(
    node: NodeBinary,
    mainScript: File,
    extensionPath: File,
    onStderrLine: (String) -> Unit,
) {
    val process: Process

    init {
        val inspectPort = System.getProperty("MNEMO_SIDECAR_INSPECT") ?: System.getenv("MNEMO_SIDECAR_INSPECT")
        val commandLine = GeneralCommandLine(node.path)
            .withWorkingDirectory(extensionPath.toPath())
            .withEnvironment("MNEMO_SIDECAR", "1")
            .withCharset(Charsets.UTF_8)
        if (!inspectPort.isNullOrBlank()) commandLine.addParameter("--inspect=127.0.0.1:$inspectPort")
        commandLine.addParameter(mainScript.path)
        LOG.info("Mnemonica: starting sidecar ${commandLine.commandLineString} in $extensionPath")
        process = commandLine.createProcess()
        thread(isDaemon = true, name = "Mnemonica sidecar stderr") {
            process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(onStderrLine) }
        }
    }

    fun destroy() {
        if (process.isAlive) process.destroy()
    }

    companion object {
        private val LOG = logger<SidecarProcess>()
    }
}
