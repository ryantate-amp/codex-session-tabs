package com.github.ryantateamp.codexsessiontabs

internal data class DiscoveredCodexSession(
    val id: String,
    val title: String,
    val cwd: String,
    val rolloutPath: String,
)

class PersistedCodexSession {
    var id: String = ""
    var title: String = ""
    var cwd: String = ""
    var rolloutPath: String = ""
    var updatedAtMillis: Long = 0

    constructor()

    internal constructor(session: DiscoveredCodexSession) {
        id = session.id
        title = session.title
        cwd = session.cwd
        rolloutPath = session.rolloutPath
        updatedAtMillis = System.currentTimeMillis()
    }
}

class CodexSessionTabsState {
    var sessions: MutableList<PersistedCodexSession> = mutableListOf()
}
