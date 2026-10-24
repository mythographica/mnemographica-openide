package ru.mythographica.mnemographica.sidecar

import com.intellij.openapi.diagnostic.logger
import org.eclipse.lsp4j.jsonrpc.Launcher
import ru.mythographica.mnemographica.rpc.HostEndpoint
import ru.mythographica.mnemographica.rpc.SidecarEndpoint
import java.io.PrintWriter
import java.io.Writer
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** lsp4j JSON-RPC connection over the sidecar's stdio. Every message is traced at debug level. */
class RpcClient(process: SidecarProcess, host: HostEndpoint) {
    val remote: SidecarEndpoint
    private val listening: Future<Void>
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "Mnemonica JSON-RPC").apply { isDaemon = true }
    }

    init {
        val builder = Launcher.Builder<SidecarEndpoint>()
            .setLocalService(host)
            .setRemoteInterface(SidecarEndpoint::class.java)
            .setInput(process.process.inputStream)
            .setOutput(process.process.outputStream)
            .setExecutorService(executor)
            // `itemId: string | null` and friends: send explicit nulls instead of dropping the field.
            .configureGson { it.serializeNulls() }
        if (LOG.isDebugEnabled) builder.traceMessages(PrintWriter(DebugLogWriter(), true))
        val launcher = builder.create()
        remote = launcher.remoteProxy
        listening = launcher.startListening()
    }

    fun close() {
        listening.cancel(true)
        executor.shutdownNow()
    }

    private class DebugLogWriter : Writer() {
        private val buffer = StringBuilder()
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            synchronized(buffer) { buffer.appendRange(cbuf, off, off + len) }
        }
        override fun flush() {
            val text = synchronized(buffer) { buffer.toString().also { buffer.setLength(0) } }
            if (text.isNotBlank()) LOG.debug(text.trimEnd())
        }
        override fun close() = flush()
    }

    companion object {
        private val LOG = logger<RpcClient>()
    }
}
