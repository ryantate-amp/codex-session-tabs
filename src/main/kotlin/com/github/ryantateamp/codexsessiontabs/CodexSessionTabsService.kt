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
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.icons.TerminalIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.content.Content
import com.intellij.util.Alarm
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
import javax.swing.Icon

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
    private val tabActivity = ConcurrentHashMap<Content, TabActivity>()

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
                coroutineScope.launch { scanTabSafely(tab) }
            }
        })

        coroutineScope.launch {
            delay(STARTUP_DELAY_MILLIS)
            try {
                restoreSavedSessions(manager)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LOG.warn("Unable to restore saved Codex sessions", error)
            }

            while (isActive && !project.isDisposed) {
                try {
                    scanTabs(manager)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    LOG.warn("Unable to scan terminal tabs; polling will continue", error)
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun scanTabs(manager: TerminalToolWindowTabsManager) {
        // Snapshot tracking first so a tab added concurrently after manager.tabs is read cannot
        // be mistaken for a tab that was closed.
        val trackedBeforeScan = tabSessions.keys.toSet()
        val decoratedBeforeScan = decoratedTabs.toSet()
        val activityBeforeScan = tabActivity.keys.toSet()
        val tabs = withContext(Dispatchers.EDT) { manager.tabs.toList() }
        tabs.forEach { scanTabSafely(it) }

        val openContents = tabs.mapTo(HashSet()) { it.content }
        val closedContents = trackedBeforeScan.filter { it !in openContents }
        synchronized(this) {
            val closedIds = closedContents.mapNotNull(tabSessions::remove).toSet()
            if (closedIds.isNotEmpty() && !project.isDisposed) {
                state.sessions.removeIf { it.id in closedIds && it.id !in tabSessions.values }
            }
        }
        decoratedBeforeScan.filter { it !in openContents }.forEach(decoratedTabs::remove)
        activityBeforeScan.filter { it !in openContents }.forEach { content ->
            tabActivity.remove(content)?.let {
                it.disposed.set(true)
                Disposer.dispose(it.disposable)
            }
        }
    }

    private suspend fun scanTabSafely(tab: TerminalToolWindowTab): DiscoveredCodexSession? = try {
        scanTab(tab)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        LOG.debug("Unable to inspect terminal tab", error)
        null
    }

    private suspend fun scanTab(tab: TerminalToolWindowTab): DiscoveredCodexSession? {
        val rootProcessId = try {
            withTimeoutOrNull(SESSION_READY_TIMEOUT_MILLIS) {
                tab.view.startupOptionsDeferred.await().pid
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LOG.debug("Unable to read the terminal process ID", error)
            null
        }
        val liveSession = if (rootProcessId == null) {
            null
        } else {
            try {
                inspector.discover(rootProcessId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LOG.debug("Unable to discover the live Codex session", error)
                null
            }
        }

        liveSession?.let { discovered ->
            decorate(tab, discovered.title)
            bindSession(tab.content, discovered)
            return discovered
        }

        return knownSession(tab.content)?.also { discovered ->
            decorate(tab, discovered.title)
            upsert(discovered)
        }
    }

    private suspend fun knownSession(content: Content): DiscoveredCodexSession? {
        val knownId = tabSessions[content] ?: return null
        val saved = synchronized(this) {
            state.sessions.firstOrNull { it.id == knownId }?.let { it.rolloutPath to it.resumeArgs.toList() }
        } ?: return null
        val rolloutPath = saved.first
        if (rolloutPath.isBlank()) return null

        return withContext(Dispatchers.IO) {
            runCatching { SessionMetadata.read(Path.of(rolloutPath)) }
                .getOrNull()
                ?.takeIf { it.id == knownId }
                ?.copy(resumeArgs = saved.second)
        }
    }

    private suspend fun decorate(tab: TerminalToolWindowTab, title: String) {
        withContext(Dispatchers.EDT) {
            ensureActivityTracking(tab)

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

    private fun ensureActivityTracking(tab: TerminalToolWindowTab) {
        if (tabActivity.containsKey(tab.content)) return

        val disposable = Disposer.newDisposable("CodexSessionTabActivity")
        Disposer.register(this, disposable)
        val idleIcon = tab.content.icon ?: TerminalIcons.Agents.Codex
        val activity = TabActivity(
            disposable = disposable,
            idleIcon = idleIcon,
            activeIcon = AnimatedIcon.Default(),
            idleAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable),
        )
        tabActivity[tab.content] = activity

        TerminalCompat.addApplicationTitleListener(tab.view, disposable) { applicationTitle ->
            if (!applicationTitle.isNullOrBlank()) markActive(tab.content, activity)
        }
    }

    private fun markActive(content: Content, activity: TabActivity) {
        if (activity.disposed.get()) return

        activity.idleAlarm.cancelAllRequests()
        activity.idleAlarm.addRequest({
            if (!activity.disposed.get() && activity.active.compareAndSet(true, false)) {
                content.icon = activity.idleIcon
            }
        }, ACTIVITY_IDLE_MILLIS)

        if (activity.active.compareAndSet(false, true)) {
            coroutineScope.launch(Dispatchers.EDT) {
                if (!activity.disposed.get()) content.icon = activity.activeIcon
            }
        }
    }

    private fun upsert(discovered: DiscoveredCodexSession) {
        synchronized(this) {
            upsertLocked(discovered)
        }
    }

    private fun bindSession(content: Content, discovered: DiscoveredCodexSession) {
        synchronized(this) {
            val previousId = tabSessions.put(content, discovered.id)
            upsertLocked(discovered)
            if (previousId != null && previousId != discovered.id && previousId !in tabSessions.values) {
                state.sessions.removeIf { it.id == previousId }
            }
        }
    }

    private fun rememberBinding(content: Content, sessionId: String) {
        synchronized(this) {
            tabSessions[content] = sessionId
        }
    }

    private fun upsertLocked(discovered: DiscoveredCodexSession) {
        val existing = state.sessions.firstOrNull { it.id == discovered.id }
        if (existing == null) {
            state.sessions.add(PersistedCodexSession(discovered))
        } else {
            existing.title = discovered.title
            existing.cwd = discovered.cwd
            existing.rolloutPath = discovered.rolloutPath
            existing.resumeArgs = discovered.resumeArgs.toMutableList()
            existing.resumeSelector = discovered.resumeSelector
            existing.updatedAtMillis = System.currentTimeMillis()
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
        val discoveredIds = tabs.mapNotNull { scanTabSafely(it)?.id }.toSet()

        for (record in records) {
            if (record.id in discoveredIds || project.isDisposed) continue

            try {
                val reusable = findReusableTab(tabs, record)
                if (reusable != null) {
                    decorate(reusable, SessionMetadata.cleanTitle(record.title))
                    rememberBinding(reusable.content, record.id)
                    sendResume(reusable, record)
                } else {
                    createRestoredTab(manager, record)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LOG.warn("Unable to restore Codex session ${record.id}", error)
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

    private suspend fun sendResume(tab: TerminalToolWindowTab, record: PersistedCodexSession) {
        val command = CodexLaunchArguments.shellResumeCommand(
            "codex",
            record.resumeSelector.ifBlank { record.id },
            record.resumeArgs,
        )
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
        val command = CodexLaunchArguments.resumeCommand(CodexExecutable.resolve(), record.id, record.resumeArgs)
        val tab = withContext(Dispatchers.EDT) {
            manager.createTabBuilder()
                .workingDirectory(record.cwd)
                .shellCommand(command)
                .processType(TerminalProcessType.NON_SHELL)
                .tabName(title)
                .requestFocus(false)
                .closeOnProcessTermination(true)
                .createTab()
        }
        rememberBinding(tab.content, record.id)
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

    override fun dispose() {
        tabActivity.values.forEach { it.disposed.set(true) }
        tabActivity.clear()
    }

    private class TabActivity(
        val disposable: Disposable,
        val idleIcon: Icon,
        val activeIcon: Icon,
        val idleAlarm: Alarm,
        val active: AtomicBoolean = AtomicBoolean(),
        val disposed: AtomicBoolean = AtomicBoolean(),
    )

    private companion object {
        val LOG = Logger.getInstance(CodexSessionTabsService::class.java)
        val SESSION_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        const val STARTUP_DELAY_MILLIS = 2_500L
        const val POLL_INTERVAL_MILLIS = 2_000L
        const val SESSION_READY_TIMEOUT_MILLIS = 150L
        const val ACTIVITY_IDLE_MILLIS = 2_000
    }
}
