package com.github.ryantateamp.codexsessiontabs

internal data class DiscoveredCodexSession(
    val id: String,
    val title: String,
    val cwd: String,
    val rolloutPath: String,
    val resumeArgs: List<String> = emptyList(),
    val resumeSelector: String = "",
)

class PersistedCodexSession {
    var id: String = ""
    var title: String = ""
    var cwd: String = ""
    var rolloutPath: String = ""
    var resumeArgs: MutableList<String> = mutableListOf()
    var resumeSelector: String = ""
    var updatedAtMillis: Long = 0

    constructor()

    internal constructor(session: DiscoveredCodexSession) {
        id = session.id
        title = session.title
        cwd = session.cwd
        rolloutPath = session.rolloutPath
        resumeArgs = session.resumeArgs.toMutableList()
        resumeSelector = session.resumeSelector
        updatedAtMillis = System.currentTimeMillis()
    }
}

class CodexSessionTabsState {
    var sessions: MutableList<PersistedCodexSession> = mutableListOf()
}

/**
 * Reorder persisted sessions only when every saved session is represented by an open tab.
 * Returning null for partial sets prevents a lazily started subset from corrupting the order
 * that will be needed to restore the remaining tabs.
 */
internal fun sessionsInOpenTabOrder(
    sessions: List<PersistedCodexSession>,
    openSessionIds: List<String>,
): List<PersistedCodexSession>? {
    val uniqueOpenIds = openSessionIds.distinct()
    val sessionsById = sessions.associateBy { it.id }
    if (sessionsById.size != sessions.size || uniqueOpenIds.size != sessions.size) return null
    if (uniqueOpenIds.toSet() != sessionsById.keys) return null
    return uniqueOpenIds.mapNotNull(sessionsById::get)
}

/** Reorder only the desired items while preserving every unrelated item's existing slot. */
internal fun <T> itemsInDesiredSlots(current: List<T>, desired: List<T>): List<T>? {
    val desiredSet = desired.toSet()
    if (desiredSet.size != desired.size || !current.containsAll(desiredSet)) return null

    val desiredIterator = desired.iterator()
    return current.map { item ->
        if (item in desiredSet) desiredIterator.next() else item
    }
}
