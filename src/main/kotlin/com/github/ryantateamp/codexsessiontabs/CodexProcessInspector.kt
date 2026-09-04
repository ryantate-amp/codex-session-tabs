package com.github.ryantateamp.codexsessiontabs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

internal class CodexProcessInspector {
    suspend fun discover(rootProcessId: Long): DiscoveredCodexSession? = withContext(Dispatchers.IO) {
        val processes = processTree(rootProcessId)
        if (processes.none(::looksLikeCodex)) return@withContext null

        val rollout = openRolloutFiles(processes.map(ProcessHandle::pid))
            .maxByOrNull { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            ?: return@withContext null

        runCatching { SessionMetadata.read(rollout) }.getOrNull()
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

    private fun openRolloutFiles(processIds: List<Long>): Set<Path> {
        if (processIds.isEmpty()) return emptySet()

        val process = ProcessBuilder(
            "lsof",
            "-Fn",
            "-p",
            processIds.distinct().joinToString(","),
        ).redirectErrorStream(true).start()

        val finished = process.waitFor(LSOF_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return emptySet()
        }

        return process.inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                if (!line.startsWith('n')) return@mapNotNull null
                val raw = line.drop(1)
                if (!raw.endsWith(".jsonl") || !raw.contains("/sessions/")) return@mapNotNull null
                val path = runCatching { Path.of(raw) }.getOrNull() ?: return@mapNotNull null
                if (!path.name.startsWith("rollout-")) return@mapNotNull null
                path.takeIf(Files::isRegularFile)
            }.toSet()
        }
    }

    private companion object {
        const val MAX_PROCESSES = 128
        const val LSOF_TIMEOUT_MILLIS = 1_500L
    }
}
