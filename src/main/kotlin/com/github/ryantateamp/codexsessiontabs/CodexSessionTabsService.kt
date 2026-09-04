package com.github.ryantateamp.codexsessiontabs

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.icons.TerminalIcons
import com.intellij.ui.content.Content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.terminal.startup.TerminalProcessType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
@State(name = "CodexSessionTabs", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class CodexSessionTabsService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : PersistentStateComponent<CodexSessionTabsState>, Disposable {
    private val started = AtomicBoolean()
    private val inspector = CodexProcessInspector()
    private val tabSessions = ConcurrentHashMap<Content, String>()
    private val decoratedTabs = ConcurrentHashMap.newKeySet<Content>()

    @Volatile
    private var state = CodexSessionTabsState()

    override fun getState(): CodexSessionTabsState = state

    override fun loadState(state: CodexSessionTabsState) {
        this.state = state
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return

        val manager = TerminalToolWindowTabsManager.getInstance(project)
        project.messageBus.connect(this).subscribe(TerminalTabsManagerListener.TOPIC, object : TerminalTabsManagerListener {
            override fun tabAdded(tab: TerminalToolWindowTab) {
                coroutineScope.launch { scanTab(tab) }
            }
        })

        coroutineScope.launch {
            delay(STARTUP_DELAY_MILLIS)
            restoreSavedSessions(manager)
            while (isActive && !project.isDisposed) {
                scanTabs(manager)
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun scanTabs(manager: TerminalToolWindowTabsManager) {
        val tabs = withContext(Dispatchers.EDT) { manager.tabs.toList() }
        tabs.forEach { scanTab(it) }

        val openContents = tabs.mapTo(HashSet()) { it.content }
        val closedContents = tabSessions.keys.filter { it !in openContents }
        val closedIds = closedContents.mapNotNull(tabSessions::remove).toSet()
        decoratedTabs.removeIf { it !in openContents }

        // Persist only tabs that were still open when the project shut down. A session the
        // user deliberately closes should not spring back on the next IDE launch.
        if (closedIds.isNotEmpty() && !project.isDisposed) {
            synchronized(this) {
                state.sessions.removeIf { it.id in closedIds }
            }
        }
    }

    private suspend fun scanTab(tab: TerminalToolWindowTab): DiscoveredCodexSession? {
        knownSession(tab.content)?.let { discovered ->
            decorate(tab, discovered.title)
            upsert(discovered)
            return discovered
        }

        val rootProcessId = withTimeoutOrNull(SESSION_READY_TIMEOUT_MILLIS) {
            tab.view.startupOptionsDeferred.await().pid
        } ?: return null

        val discovered = runCatching { inspector.discover(rootProcessId) }
            .onFailure { if (it !is CancellationException) LOG.debug("Codex session discovery failed", it) }
            .getOrNull()
            ?: return null

        decorate(tab, discovered.title)
        tabSessions[tab.content] = discovered.id
        upsert(discovered)
        return discovered
    }

    private suspend fun knownSession(content: Content): DiscoveredCodexSession? {
        val knownId = tabSessions[content] ?: return null
        val rolloutPath = synchronized(this) {
            state.sessions.firstOrNull { it.id == knownId }?.rolloutPath
        }.orEmpty()
        if (rolloutPath.isBlank()) return null

        return withContext(Dispatchers.IO) {
            runCatching { SessionMetadata.read(Path.of(rolloutPath)) }
                .getOrNull()
                ?.takeIf { it.id == knownId }
        }
    }

    private suspend fun decorate(tab: TerminalToolWindowTab, title: String) {
        withContext(Dispatchers.EDT) {
            if (tab.view.title.buildTitle() != title) {
                TerminalCompat.setUserDefinedTitle(tab.view, title)
            }

            tab.content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
            if (decoratedTabs.add(tab.content) || tab.content.icon == null) {
                // Keep an icon already supplied by JetBrains: it may be a stateful/animated
                // variant. Restored generic tabs receive the same Codex base icon used by the
                // built-in AI Agents launcher.
                if (tab.content.icon == null) tab.content.icon = TerminalIcons.Agents.Codex
            }
        }
    }

    private fun upsert(discovered: DiscoveredCodexSession) {
        synchronized(this) {
            val existing = state.sessions.firstOrNull { it.id == discovered.id }
            if (existing == null) {
                state.sessions.add(PersistedCodexSession(discovered))
            } else {
                existing.title = discovered.title
                existing.cwd = discovered.cwd
                existing.rolloutPath = discovered.rolloutPath
                existing.updatedAtMillis = System.currentTimeMillis()
            }
        }
    }

    private suspend fun restoreSavedSessions(manager: TerminalToolWindowTabsManager) {
        val records = synchronized(this) {
            state.sessions
                .filter { SESSION_ID.matches(it.id) }
                .sortedBy { it.title }
                .toList()
        }
        if (records.isEmpty()) return

        val tabs = withContext(Dispatchers.EDT) { manager.tabs.toList() }
        val discoveredIds = tabs.mapNotNull { scanTab(it)?.id }.toSet()

        for (record in records) {
            if (record.id in discoveredIds || project.isDisposed) continue

            val reusable = findReusableTab(tabs, record)
            if (reusable != null) {
                decorate(reusable, SessionMetadata.cleanTitle(record.title))
                tabSessions[reusable.content] = record.id
                sendResume(reusable, record.id)
            } else {
                createRestoredTab(manager, record)
            }
        }
    }

    private suspend fun findReusableTab(
        tabs: List<TerminalToolWindowTab>,
        record: PersistedCodexSession,
    ): TerminalToolWindowTab? {
        val expectedTitle = SessionMetadata.cleanTitle(record.title)
        val matches = mutableListOf<TerminalToolWindowTab>()
        for (tab in tabs) {
            if (tabSessions.containsKey(tab.content)) continue
            val title = withContext(Dispatchers.EDT) { tab.view.title.buildTitle() }
            if (SessionMetadata.cleanTitle(title) != expectedTitle) continue
            if (sameDirectory(TerminalCompat.currentDirectory(tab.view), record.cwd)) matches.add(tab)
        }
        if (matches.size != 1) return null

        val candidate = matches.single()
        return if (runCatching { !candidate.view.hasChildProcesses() }.getOrDefault(false)) candidate else null
    }

    private suspend fun sendResume(tab: TerminalToolWindowTab, sessionId: String) {
        val command = "${shellQuote(CodexExecutable.resolve())} resume ${shellQuote(sessionId)}"
        withContext(Dispatchers.EDT) {
            tab.view.createSendTextBuilder()
                .useBracketedPasteMode()
                .shouldExecute()
                .send(command)
        }
    }

    private suspend fun createRestoredTab(
        manager: TerminalToolWindowTabsManager,
        record: PersistedCodexSession,
    ) {
        val title = SessionMetadata.cleanTitle(record.title).ifBlank { record.id.take(8) }
        val tab = withContext(Dispatchers.EDT) {
            manager.createTabBuilder()
                .workingDirectory(record.cwd)
                .shellCommand(listOf(CodexExecutable.resolve(), "resume", record.id))
                .processType(TerminalProcessType.NON_SHELL)
                .tabName(title)
                .requestFocus(false)
                .closeOnProcessTermination(true)
                .createTab()
        }
        tabSessions[tab.content] = record.id
        decorate(tab, title)
    }

    private fun sameDirectory(actual: String?, expected: String): Boolean {
        if (actual.isNullOrBlank() || expected.isBlank()) return false
        return runCatching {
            val actualPath = Path.of(actual)
            val expectedPath = Path.of(expected)
            val normalizedActual = if (Files.exists(actualPath)) actualPath.toRealPath() else actualPath.toAbsolutePath().normalize()
            val normalizedExpected = if (Files.exists(expectedPath)) expectedPath.toRealPath() else expectedPath.toAbsolutePath().normalize()
            normalizedActual == normalizedExpected
        }.getOrDefault(false)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    override fun dispose() = Unit

    private companion object {
        val LOG = Logger.getInstance(CodexSessionTabsService::class.java)
        val SESSION_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        const val STARTUP_DELAY_MILLIS = 2_500L
        const val POLL_INTERVAL_MILLIS = 2_000L
        const val SESSION_READY_TIMEOUT_MILLIS = 150L
    }
}
