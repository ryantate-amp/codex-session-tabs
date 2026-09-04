package com.github.ryantateamp.codexsessiontabs

import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.io.path.writeLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionMetadataTest {
    @Test
    fun `uses the newest index entry for a session`() {
        val index = createTempDirectory().resolve("session_index.jsonl")
        index.writeLines(
            listOf(
                """{"id":"abc","thread_name":"old","updated_at":"1"}""",
                """{"id":"other","thread_name":"ignore","updated_at":"2"}""",
                """{"id":"abc","thread_name":"new","updated_at":"3"}""",
            ),
        )

        assertEquals("new", SessionMetadata.readThreadName(index, "abc"))
        assertEquals("new", SessionMetadata.readUniqueThreadName(index, "abc"))
        assertNull(SessionMetadata.readThreadName(index, "missing"))
    }

    @Test
    fun `does not use a duplicate thread name as a resume selector`() {
        val index = createTempDirectory().resolve("session_index.jsonl")
        index.writeLines(
            listOf(
                """{"id":"abc","thread_name":"same"}""",
                """{"id":"other","thread_name":"same"}""",
            ),
        )

        assertNull(SessionMetadata.readUniqueThreadName(index, "abc"))
    }

    @Test
    fun `refreshes the cached index after it changes`() {
        val index = createTempDirectory().resolve("session_index.jsonl")
        index.writeText("""{"id":"abc","thread_name":"old"}""")

        assertEquals("old", SessionMetadata.readThreadName(index, "abc"))

        index.appendText("\n{\"id\":\"abc\",\"thread_name\":\"new\"}\n")

        assertEquals("new", SessionMetadata.readThreadName(index, "abc"))
    }

    @Test
    fun `normalizes decorated titles without changing normal names`() {
        assertEquals("app-lb", SessionMetadata.cleanTitle("Codi: app-lb"))
        assertEquals("dash-shipyard", SessionMetadata.cleanTitle("Codex: dash-shipyard"))
        assertEquals("karpenter", SessionMetadata.cleanTitle("karpenter"))
    }

    @Test
    fun `derives codex home from dated rollout directory`() {
        val root = createTempDirectory()
        val rollout = root.resolve("sessions/2026/09/04/rollout-id.jsonl")
        rollout.parent.createDirectories()

        assertEquals(root, SessionMetadata.codexHomeFor(rollout))
    }

    @Test
    fun `reads UUIDv7 rollout metadata and normalizes its title`() {
        val root = createTempDirectory()
        val id = "01991ac0-7d72-7f15-8fb4-c63e900a52d0"
        val rollout = root.resolve("sessions/2026/09/04/rollout-$id.jsonl")
        rollout.parent.createDirectories()
        rollout.writeText(
            """{"type":"session_meta","payload":{"id":"$id","cwd":"/tmp/app-lb"}}""",
        )
        root.resolve("session_index.jsonl").writeText(
            """{"id":"$id","thread_name":"Codi: app-lb","updated_at":"2026-09-04T00:00:00Z"}""",
        )

        assertEquals(
            DiscoveredCodexSession(
                id = id,
                title = "app-lb",
                cwd = "/tmp/app-lb",
                rolloutPath = rollout.toString(),
                resumeSelector = "Codi: app-lb",
            ),
            SessionMetadata.read(rollout),
        )
    }
}
