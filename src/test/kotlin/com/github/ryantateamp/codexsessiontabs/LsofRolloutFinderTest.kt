package com.github.ryantateamp.codexsessiontabs

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LsofRolloutFinderTest {
    @Test
    fun `drains output while lsof is running`() = runBlocking {
        val root = createTempDirectory()
        val rollout = root.resolve("sessions/2026/09/04/rollout-test.jsonl")
        rollout.parent.createDirectories()
        rollout.writeText("{}")
        val command = executableScript(
            root.resolve("large-lsof-output"),
            """
                awk 'BEGIN { for (i = 0; i < 20000; i++) print "n/tmp/unrelated-" i }'
                printf 'n%s\n' ${shellQuote(rollout.toString())}
            """.trimIndent(),
        )

        val finder = LsofRolloutFinder(
            commandPrefix = listOf(command.toString()),
            timeoutMillis = 5_000,
        )

        assertEquals(setOf(rollout), finder.find(listOf(123)))
    }

    @Test
    fun `terminates lsof after its timeout`() = runBlocking {
        val root = createTempDirectory()
        val command = executableScript(root.resolve("slow-lsof"), "exec sleep 10")
        val finder = LsofRolloutFinder(
            commandPrefix = listOf(command.toString()),
            timeoutMillis = 100,
            terminationTimeoutMillis = 500,
        )

        var result: Set<Path> = setOf(Path.of("unexpected"))
        val elapsedMillis = measureTimeMillis {
            result = finder.find(listOf(123))
        }

        assertEquals(emptySet(), result)
        assertTrue(elapsedMillis < 2_000, "timed-out lsof took ${elapsedMillis}ms")
    }

    private fun executableScript(path: Path, body: String): Path {
        path.writeText("#!/bin/sh\n$body\n")
        assertTrue(path.toFile().setExecutable(true), "could not make $path executable")
        return path
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
