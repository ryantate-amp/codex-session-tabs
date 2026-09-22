package com.github.ryantateamp.codexsessiontabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionRecordTest {
    @Test
    fun `reorders a complete saved set to match open tab order`() {
        val sessions = listOf(session("dash"), session("k8s"), session("redpanda"))

        val reordered = sessionsInOpenTabOrder(sessions, listOf("k8s", "dash", "redpanda"))

        assertEquals(listOf("k8s", "dash", "redpanda"), reordered?.map { it.id })
    }

    @Test
    fun `does not corrupt saved order when only a subset has started`() {
        val sessions = listOf(session("k8s"), session("dash"), session("redpanda"))

        assertNull(sessionsInOpenTabOrder(sessions, listOf("redpanda")))
    }

    @Test
    fun `does not reorder when open tabs contain a different session set`() {
        val sessions = listOf(session("k8s"), session("dash"))

        assertNull(sessionsInOpenTabOrder(sessions, listOf("k8s", "other")))
    }

    @Test
    fun `restores managed items while retaining unrelated slots`() {
        val restored = itemsInDesiredSlots(
            current = listOf("k8s", "Local", "dash", "thunder"),
            desired = listOf("thunder", "dash", "k8s"),
        )

        assertEquals(listOf("thunder", "Local", "dash", "k8s"), restored)
    }

    @Test
    fun `refuses to restore an incomplete desired item set`() {
        assertNull(itemsInDesiredSlots(listOf("k8s", "dash"), listOf("thunder", "k8s")))
    }

    private fun session(id: String) = PersistedCodexSession().also { it.id = id }
}
