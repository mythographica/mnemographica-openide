package ru.mythographica.mnemographica.sidecar

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File

/** A usable Node binary and its version. */
data class NodeBinary(val path: String, val version: String)

/**
 * Finds Node >= [MIN_MAJOR]: settings value, then PATH, then the newest nvm install, then the usual Homebrew/system paths.
 * Blocking (runs `node --version`); call it off the EDT.
 */
object NodeLocator {
    const val MIN_MAJOR = 18
    private val LOG = logger<NodeLocator>()
    private val exeName = if (SystemInfo.isWindows) "node.exe" else "node"

    fun locate(configuredPath: String?): NodeBinary? {
        for (candidate in candidates(configuredPath)) {
            val version = versionOf(candidate) ?: continue
            if (majorOf(version) >= MIN_MAJOR) return NodeBinary(candidate, version)
            LOG.info("Mnemonica: skipping $candidate ($version < v$MIN_MAJOR)")
        }
        return null
    }

    private fun candidates(configuredPath: String?): Sequence<String> = sequence {
        if (!configuredPath.isNullOrBlank()) {
            yield(configuredPath.trim())
            return@sequence
        }
        PathEnvironmentVariableUtil.findInPath(exeName)?.let { yield(it.path) }
        yieldAll(nvmNodes())
        if (!SystemInfo.isWindows) {
            yield("/opt/homebrew/bin/node")
            yield("/usr/local/bin/node")
            yield("/usr/bin/node")
        }
    }.filter { File(it).canExecute() }.distinct()

    private fun nvmNodes(): List<String> {
        val nvmDir = System.getenv("NVM_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".nvm")
        val versions = File(nvmDir, "versions/node").listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return versions
            .sortedWith(compareByDescending<File> { parseVersion(it.name)[0] }
                .thenByDescending { parseVersion(it.name)[1] }
                .thenByDescending { parseVersion(it.name)[2] })
            .map { File(it, "bin/$exeName").path }
    }

    private fun versionOf(path: String): String? = try {
        val output = ExecUtil.execAndGetOutput(GeneralCommandLine(path, "--version"), 5_000)
        output.stdout.trim().takeIf { output.exitCode == 0 && it.startsWith("v") }
    } catch (e: Exception) {
        LOG.info("Mnemonica: cannot run $path --version: ${e.message}")
        null
    }

    internal fun majorOf(version: String): Int = parseVersion(version)[0]

    internal fun parseVersion(text: String): IntArray {
        val parts = text.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return IntArray(3) { parts.getOrElse(it) { 0 } }
    }
}
