package com.github.ryantateamp.codexsessiontabs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

internal class CodexProcessInspector(
    private val rolloutFinder: LsofRolloutFinder = LsofRolloutFinder(),
) {
    suspend fun discover(rootProcessId: Long): DiscoveredCodexSession? = withContext(Dispatchers.IO) {
        val processes = processTree(rootProcessId)
        val codexProcess = processes.firstOrNull(::isDirectCodexProcess)
            ?: processes.firstOrNull(::looksLikeCodex)
            ?: return@withContext null

        val rollout = rolloutFinder.find(processes.map(ProcessHandle::pid))
            .maxByOrNull { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            ?: return@withContext null

        runCatching { SessionMetadata.read(rollout) }
            .getOrNull()
            ?.copy(resumeArgs = CodexLaunchArguments.forResume(processArguments(codexProcess)))
    }

    private fun processTree(rootProcessId: Long): List<ProcessHandle> {
        val root = ProcessHandle.of(rootProcessId).orElse(null) ?: return emptyList()
        return buildList {
            add(root)
            root.descendants().limit(MAX_PROCESSES.toLong()).forEach(::add)
        }
    }

    private fun looksLikeCodex(process: ProcessHandle): Boolean {
        val info = process.info()
        val command = info.command().orElse("").lowercase()
        val commandLine = info.commandLine().orElse("").lowercase()
        val arguments = info.arguments().orElse(emptyArray()).joinToString(" ").lowercase()
        return command.substringAfterLast('/').startsWith("codex") ||
            commandLine.contains("/codex") ||
            arguments.contains("@openai/codex")
    }

    private fun isDirectCodexProcess(process: ProcessHandle): Boolean {
        val command = process.info().command().orElse("")
        return command.substringAfterLast('/').lowercase().startsWith("codex")
    }

    private fun processArguments(process: ProcessHandle): List<String> {
        val info = process.info()
        val arguments = info.arguments().orElse(emptyArray()).toList()
        if (isDirectCodexProcess(process)) return arguments

        // npm installations may briefly expose a Node launcher instead of the native Codex
        // process. Its first argument is the launcher script, not a user-supplied CLI argument.
        val first = arguments.firstOrNull()?.lowercase().orEmpty()
        return if (first.contains("@openai/codex") || first.endsWith("/codex.js")) arguments.drop(1) else arguments
    }

    private companion object {
        const val MAX_PROCESSES = 128
    }
}

internal class LsofRolloutFinder(
    private val commandPrefix: List<String> = listOf("lsof"),
    private val timeoutMillis: Long = LSOF_TIMEOUT_MILLIS,
    private val terminationTimeoutMillis: Long = LSOF_TERMINATION_TIMEOUT_MILLIS,
) {
    suspend fun find(processIds: List<Long>): Set<Path> = coroutineScope {
        if (processIds.isEmpty()) return@coroutineScope emptySet()

        val process = ProcessBuilder(
            commandPrefix + listOf("-Fn", "-p", processIds.distinct().joinToString(",")),
        ).redirectErrorStream(true).start()
        val rolloutPaths = async(Dispatchers.IO) { readRolloutPaths(process) }

        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                try {
                    process.inputStream.close()
                } catch (_: IOException) {
                    // Closing a process pipe may race with process termination.
                }
                process.waitFor(terminationTimeoutMillis, TimeUnit.MILLISECONDS)
                rolloutPaths.cancelAndJoin()
                return@coroutineScope emptySet()
            }

            rolloutPaths.await()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun readRolloutPaths(process: Process): Set<Path> = try {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                if (!line.startsWith('n')) return@mapNotNull null
                val raw = line.drop(1)
                if (!raw.endsWith(".jsonl") || !raw.contains("/sessions/")) return@mapNotNull null
                val path = runCatching { Path.of(raw) }.getOrNull() ?: return@mapNotNull null
                if (!path.name.startsWith("rollout-")) return@mapNotNull null
                path.takeIf(Files::isRegularFile)
            }.toSet()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        emptySet()
    }

    private companion object {
        const val LSOF_TIMEOUT_MILLIS = 1_500L
        const val LSOF_TERMINATION_TIMEOUT_MILLIS = 500L
    }
}
