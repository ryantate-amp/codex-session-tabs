package com.github.ryantateamp.codexsessiontabs

import kotlin.test.Test
import kotlin.test.assertEquals

class CodexLaunchArgumentsTest {
    @Test
    fun `retains yolo before an existing resume without duplicating resume`() {
        val sessionId = "019c1234-5678-7abc-8def-0123456789ab"

        assertEquals(
            listOf("codex", "--yolo", "resume", sessionId),
            CodexLaunchArguments.resumeCommand(
                "codex",
                sessionId,
                listOf("--yolo", "resume", sessionId),
            ),
        )
    }

    @Test
    fun `collects reusable flags around a prior resume and drops its id and prompt`() {
        assertEquals(
            listOf("--yolo", "--model", "gpt-5.6-sol", "-c", "model_reasoning_effort=high", "--search"),
            CodexLaunchArguments.forResume(
                listOf(
                    "--yolo",
                    "--model",
                    "gpt-5.6-sol",
                    "resume",
                    "019c1234-5678-7abc-8def-0123456789ab",
                    "continue this",
                    "-c",
                    "model_reasoning_effort=high",
                    "--search",
                ),
            ),
        )
    }

    @Test
    fun `drops positional and one-shot arguments`() {
        assertEquals(
            listOf("--sandbox", "workspace-write", "--add-dir=/tmp/shared"),
            CodexLaunchArguments.forResume(
                listOf(
                    "--cd",
                    "/tmp/project",
                    "--image",
                    "one.png",
                    "two.png",
                    "--sandbox",
                    "workspace-write",
                    "--add-dir=/tmp/shared",
                    "initial prompt",
                ),
            ),
        )
    }

    @Test
    fun `renders a human-readable command for an existing shell tab`() {
        assertEquals(
            "codex --yolo resume this-is-a-test",
            CodexLaunchArguments.shellResumeCommand(
                "codex",
                "this-is-a-test",
                listOf("--yolo"),
            ),
        )
    }

    @Test
    fun `quotes only shell words that need it`() {
        assertEquals(
            "'/opt/Codex CLI/codex' -c 'name=it'\\''s' resume 'session name'",
            CodexLaunchArguments.shellResumeCommand(
                "/opt/Codex CLI/codex",
                "session name",
                listOf("-c", "name=it's"),
            ),
        )
    }

    @Test
    fun `recognizes a persisted command for the exact resumed session`() {
        val sessionId = "019c1234-5678-7abc-8def-0123456789ab"

        assertEquals(
            true,
            CodexLaunchArguments.resumesSession(
                listOf("/opt/homebrew/bin/codex", "--yolo", "resume", sessionId),
                sessionId,
            ),
        )
        assertEquals(
            false,
            CodexLaunchArguments.resumesSession(
                listOf("/opt/homebrew/bin/codex", "--yolo", "resume", "a-different-session"),
                sessionId,
            ),
        )
        assertEquals(
            false,
            CodexLaunchArguments.resumesSession(listOf("/bin/zsh", "-l"), sessionId),
        )
    }

    @Test
    fun `extracts a human resume selector from app server client arguments`() {
        assertEquals(
            "k8s-platform",
            CodexLaunchArguments.resumeSelector(listOf("--yolo", "resume", "k8s-platform")),
        )
    }

    @Test
    fun `does not guess a selector for resume last or a normal launch`() {
        assertEquals(null, CodexLaunchArguments.resumeSelector(listOf("resume", "--last")))
        assertEquals(null, CodexLaunchArguments.resumeSelector(listOf("--yolo")))
    }
}
