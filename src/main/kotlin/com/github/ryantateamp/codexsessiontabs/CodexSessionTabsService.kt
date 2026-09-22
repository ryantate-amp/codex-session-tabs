package com.github.ryantateamp.codexsessiontabs

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.PersistentStateComponentWithModificationTracker
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.icons.TerminalIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.util.Alarm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.plugins.terminal.startup.TerminalProcessType
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.Icon
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Service(Service.Level.PROJECT)
@State(name = "CodexSessionTabs", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class CodexSessionTabsService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : PersistentStateComponentWithModificationTracker<CodexSessionTabsState>, Disposable {
    private val started = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val initialTabOrderRestored = AtomicBoolean()
    private val reorderingTabs = AtomicBoolean()
    private val inspector = CodexProcessInspector()
    private val restoreMutex = Mutex()
    private val tabSessions = ConcurrentHashMap<Content, String>()
    private val decoratedTabs = ConcurrentHashMap.newKeySet<Content>()
    private val tabActivity = ConcurrentHashMap<Content, TabActivity>()
    private val stateModificationCount = AtomicLong()
    private val pruneUnmatchedAfterNanos = AtomicLong(System.nanoTime() + RESTORE_SETTLE_NANOS)

    @Volatile
    private var state = CodexSessionTabsState()

    override fun getState(): CodexSessionTabsState = state

    override fun getStateModificationCount(): Long = stateModificationCount.get()

    override fun loadState(state: CodexSessionTabsState) {
        this.state = state
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return

        val manager = TerminalToolWindowTabsManager.getInstance(project)
        project.messageBus.connect(this).subscribe(TerminalTabsManagerListener.TOPIC, object : TerminalTabsManagerListener {
            override fun tabAdded(tab: TerminalToolWindowTab) {
                pruneUnmatchedAfterNanos.set(System.nanoTime() + RESTORE_SETTLE_NANOS)
                coroutineScope.launch {
                    scanTabSafely(tab)
                    restoreSavedSessions(manager)
                }
            }
        })
        project.messageBus.connect(this).subscribe(ProjectCloseListener.TOPIC, object : ProjectCloseListener {
            override fun projectClosingBeforeSave(project: Project) {
                if (project === this@CodexSessionTabsService.project) closing.set(true)
            }

            override fun projectClosing(project: Project) {
                if (project === this@CodexSessionTabsService.project) closing.set(true)
            }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AppLifecycleListener.TOPIC,
            object : AppLifecycleListener {
                override fun appClosing() {
                    closing.set(true)
                }
            },
        )

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
                    restoreSavedSessions(manager)
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
        if (reorderingTabs.get()) return

        // Snapshot tracking first so a tab added concurrently after manager.tabs is read cannot
        // be mistaken for a tab that was closed.
        val trackedBeforeScan = tabSessions.keys.toSet()
        val decoratedBeforeScan = decoratedTabs.toSet()
        val activityBeforeScan = tabActivity.keys.toSet()
        val tabs = snapshotTabsInDisplayOrder(manager)
        tabs.forEach { scanTabSafely(it) }

        val openContents = tabs.mapTo(HashSet()) { it.content }
        val closedContents = trackedBeforeScan.filter { it !in openContents }
        synchronized(this) {
            val closedIds = closedContents.mapNotNull(tabSessions::remove).toSet()
            if (closedIds.isNotEmpty() && !closing.get() && !project.isDisposed) {
                if (state.sessions.removeIf { it.id in closedIds && it.id !in tabSessions.values }) {
                    stateChanged()
                }
            }
            if (initialTabOrderRestored.get()) syncSessionOrderLocked(tabs)
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
                inspector.discover(
                    rootProcessId,
                    savedSessionHints(),
                    TerminalCompat.applicationTitle(tab.view),
                )
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

    private fun savedSessionHints(): List<DiscoveredCodexSession> = synchronized(this) {
        state.sessions.map { session ->
            DiscoveredCodexSession(
                id = session.id,
                title = session.title,
                cwd = session.cwd,
                rolloutPath = session.rolloutPath,
                resumeArgs = session.resumeArgs.toList(),
                resumeSelector = session.resumeSelector,
            )
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
                if (state.sessions.removeIf { it.id == previousId }) stateChanged()
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
            stateChanged()
        } else {
            val changed = existing.title != discovered.title ||
                existing.cwd != discovered.cwd ||
                existing.rolloutPath != discovered.rolloutPath ||
                existing.resumeArgs != discovered.resumeArgs ||
                existing.resumeSelector != discovered.resumeSelector
            if (changed) {
                existing.title = discovered.title
                existing.cwd = discovered.cwd
                existing.rolloutPath = discovered.rolloutPath
                existing.resumeArgs = discovered.resumeArgs.toMutableList()
                existing.resumeSelector = discovered.resumeSelector
                existing.updatedAtMillis = System.currentTimeMillis()
                stateChanged()
            }
        }
    }

    private suspend fun restoreSavedSessions(manager: TerminalToolWindowTabsManager) = restoreMutex.withLock {
        if (closing.get() || project.isDisposed) return@withLock

        val tabs = snapshotTabsInDisplayOrder(manager)
        val alreadyBound = tabSessions.values.toSet()
        val records = synchronized(this) {
            state.sessions
                .filter { SESSION_ID.matches(it.id) && it.id !in alreadyBound }
                .distinctBy { it.id }
                .toMutableList()
        }
        if (records.isEmpty()) {
            reconcileSessionOrder(manager, tabs)
            return@withLock
        }

        val discoveredIds = tabs.mapNotNull { scanTabSafely(it)?.id }.toSet()
        records.removeIf { it.id in discoveredIds || it.id in tabSessions.values }
        if (records.isEmpty()) {
            reconcileSessionOrder(manager, tabs)
            return@withLock
        }

        val candidates = snapshotRestoreTabs(tabs)
            .filter { !tabSessions.containsKey(it.tab.content) }
            .toMutableList()

        // A custom command persisted by JetBrains is authoritative. It will be started lazily
        // when its tab is shown; sending text here would inject a second command into Codex.
        for (record in records.toList()) {
            val matches = candidates.filter {
                CodexLaunchArguments.resumesSession(it.command, record.id)
            }
            if (matches.size == 1) {
                bindRestoredTab(matches.single(), record, sendCommand = false)
                records.remove(record)
                candidates.remove(matches.single())
            }
        }

        // Match mutually unique title/directory pairs. Requested working-directory metadata is
        // available even for JetBrains' dormant tabs, unlike TerminalView.currentDirectory.
        val exactMatches = records.associateWith { record ->
            candidates.filter { candidate ->
                candidate.idleShell &&
                    candidate.title == SessionMetadata.cleanTitle(record.title) &&
                    candidate.directories.any { sameDirectory(it, record.cwd) }
            }
        }
        val exactPairs = exactMatches.mapNotNull { (record, matches) ->
            val candidate = matches.singleOrNull() ?: return@mapNotNull null
            val claimants = exactMatches.count { candidate in it.value }
            if (claimants == 1) record to candidate else null
        }
        for ((record, candidate) in exactPairs) {
            bindRestoredTab(candidate, record, sendCommand = true)
            records.remove(record)
            candidates.remove(candidate)
        }

        // Older 2026.2 builds do not expose requested process options for dormant tabs. A unique
        // persisted user title is still sufficient to identify a tab without relying on a live
        // process or its current directory.
        val titleMatches = records.associateWith { record ->
            candidates.filter { candidate ->
                candidate.idleShell && candidate.title == SessionMetadata.cleanTitle(record.title)
            }
        }
        val titlePairs = titleMatches.mapNotNull { (record, matches) ->
            val candidate = matches.singleOrNull() ?: return@mapNotNull null
            val claimants = titleMatches.count { candidate in it.value }
            if (claimants == 1) record to candidate else null
        }
        for ((record, candidate) in titlePairs) {
            bindRestoredTab(candidate, record, sendCommand = true)
            records.remove(record)
            candidates.remove(candidate)
        }

        // Application titles are intentionally not persisted by JetBrains. If they were the only
        // identifying title, pair the remaining idle shell tabs by directory only when the counts
        // agree exactly. This recovers all sessions without guessing or creating duplicate tabs.
        val recordsByDirectory = records.groupBy { directoryKey(it.cwd) }.filterKeys { it != null }
        val tabsByDirectory = candidates
            .filter { it.idleShell }
            .groupBy { candidate -> candidate.directories.firstNotNullOfOrNull(::directoryKey) }
            .filterKeys { it != null }
        for ((directory, directoryRecords) in recordsByDirectory) {
            val directoryTabs = tabsByDirectory[directory].orEmpty()
            if (directoryTabs.size != directoryRecords.size) continue

            directoryRecords.zip(directoryTabs).forEach { (record, candidate) ->
                bindRestoredTab(candidate, record, sendCommand = true)
                records.remove(record)
                candidates.remove(candidate)
            }
        }

        // A one-time recovery/install may have only generic JetBrains titles to work with. If the
        // complete saved set and complete idle-tab set have equal cardinality and every saved
        // session belongs to one directory, pairing in tab order is deterministic and does not
        // create or close anything. Refuse partial sets or additional unrelated tabs.
        val expectedDirectories = records.mapNotNull { directoryKey(it.cwd) }.distinct()
        val genericIdleTabs = candidates.filter { it.idleShell && isGenericTerminalTitle(it.title) }
        if (records.isNotEmpty() &&
            expectedDirectories.size == 1 &&
            genericIdleTabs.size == records.size &&
            candidates.size == records.size
        ) {
            records.zip(genericIdleTabs).forEach { (record, candidate) ->
                bindRestoredTab(candidate, record, sendCommand = true)
                records.remove(record)
                candidates.remove(candidate)
            }
        }

        if (records.isNotEmpty()) {
            val staleRecords = if (canPruneUnmatchedSessions()) {
                records.filter { record -> candidates.none { plausibleRestoreCandidate(it, record) } }
            } else {
                emptyList()
            }
            if (staleRecords.isNotEmpty()) {
                val staleIds = staleRecords.mapTo(HashSet()) { it.id }
                synchronized(this) {
                    if (state.sessions.removeIf { it.id in staleIds && it.id !in tabSessions.values }) {
                        stateChanged()
                    }
                }
                records.removeAll(staleRecords.toSet())
                LOG.info("Removed ${staleRecords.size} saved Codex session(s) with no open terminal tab")
            }
            if (records.isNotEmpty()) {
                LOG.info(
                    "Left ${records.size} Codex session(s) unrestored because no unambiguous " +
                        "JetBrains terminal tab was available",
                )
            }
        }

        reconcileSessionOrder(manager, tabs)
    }

    private fun plausibleRestoreCandidate(candidate: RestoreTab, record: PersistedCodexSession): Boolean {
        if (CodexLaunchArguments.resumesSession(candidate.command, record.id)) return true
        return candidate.title == SessionMetadata.cleanTitle(record.title)
    }

    private fun canPruneUnmatchedSessions(): Boolean =
        !closing.get() &&
            tabSessions.isNotEmpty() &&
            System.nanoTime() >= pruneUnmatchedAfterNanos.get()

    private fun syncSessionOrderLocked(tabs: List<TerminalToolWindowTab>) {
        val openSessionIds = tabs.mapNotNull { tabSessions[it.content] }
        val reordered = sessionsInOpenTabOrder(state.sessions, openSessionIds) ?: return
        if (reordered.map { it.id } != state.sessions.map { it.id }) {
            state.sessions = reordered.toMutableList()
            stateChanged()
        }
    }

    private suspend fun reconcileSessionOrder(
        manager: TerminalToolWindowTabsManager,
        tabs: List<TerminalToolWindowTab>,
    ) {
        var orderedTabs = tabs
        if (!initialTabOrderRestored.get()) {
            if (!restoreInitialTabOrder(tabs)) return
            initialTabOrderRestored.set(true)
            orderedTabs = snapshotTabsInDisplayOrder(manager)
        }
        synchronized(this) { syncSessionOrderLocked(orderedTabs) }
    }

    /**
     * JetBrains restores terminal contents in their creation order. Once every saved session has
     * an unambiguous tab binding, physically move those contents into the saved display order.
     * Unrelated terminal tabs retain their positions, and this runs only once so later user drag
     * operations remain authoritative.
     */
    private suspend fun restoreInitialTabOrder(tabs: List<TerminalToolWindowTab>): Boolean {
        val desiredIds = synchronized(this) { state.sessions.map { it.id } }
        if (desiredIds.isEmpty()) return true

        val tabsById = tabs.mapNotNull { tab ->
            tabSessions[tab.content]?.let { sessionId -> sessionId to tab }
        }.toMap()
        if (tabsById.size != desiredIds.size || tabsById.keys != desiredIds.toSet()) return false

        val desiredTabs = desiredIds.mapNotNull(tabsById::get)
        if (desiredTabs.size != desiredIds.size || desiredTabs.any { it.content.manager == null }) return false

        reorderingTabs.set(true)
        try {
            val managers = desiredTabs.mapNotNull { it.content.manager }.distinct()
            for (contentManager in managers) {
                val desiredContents = desiredTabs
                    .filter { it.content.manager === contentManager }
                    .map { it.content }
                reorderTerminalContents(contentManager, desiredContents)
            }
        } finally {
            reorderingTabs.set(false)
        }
        return true
    }

    private suspend fun reorderTerminalContents(
        contentManager: ContentManager,
        desiredContents: List<Content>,
    ) {
        if (desiredContents.size < 2) return

        val targetContents = withContext(Dispatchers.EDT) {
            itemsInDesiredSlots(contentManager.contents.toList(), desiredContents)
        } ?: throw IllegalStateException("Saved terminal contents no longer match the open tabs")
        val selectedContent = withContext(Dispatchers.EDT) { contentManager.selectedContent }

        for ((targetIndex, desiredContent) in targetContents.withIndex()) {
            while (true) {
                val currentIndex = withContext(Dispatchers.EDT) {
                    contentManager.getIndexOfContent(desiredContent)
                }
                if (currentIndex < 0) {
                    throw IllegalStateException("Terminal content disappeared while restoring tab order")
                }
                if (currentIndex <= targetIndex) break

                // Use the same adjacent-swap operation as JetBrains' terminal move actions.
                val precedingContent = withContext(Dispatchers.EDT) {
                    contentManager.getContent(currentIndex - 1)
                } ?: throw IllegalStateException("Unable to find terminal content at index ${currentIndex - 1}")
                moveContentSilently(contentManager, precedingContent, currentIndex)
            }
        }

        if (selectedContent != null) {
            withContext(Dispatchers.EDT) {
                if (contentManager.getIndexOfContent(selectedContent) >= 0) {
                    contentManager.setSelectedContent(selectedContent)
                }
            }
        }
    }

    private suspend fun moveContentSilently(
        contentManager: ContentManager,
        content: Content,
        targetIndex: Int,
    ) = withContext(Dispatchers.EDT) {
        suspendCancellableCoroutine { continuation ->
            content.putUserData(Content.TEMPORARY_REMOVED_KEY, true)
            try {
                contentManager.removeContent(content, false, false, false)
                    .doWhenDone {
                        try {
                            contentManager.addContent(content, targetIndex)
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                    .doWhenRejected { error ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException(error ?: "Unable to reorder terminal content"),
                            )
                        }
                    }
            } finally {
                content.putUserData(Content.TEMPORARY_REMOVED_KEY, null)
            }
        }
    }

    /**
     * [TerminalToolWindowTabsManager.tabs] retains tab creation order when a user drags tabs.
     * The content manager owns the order rendered in the tool window, so persistence and restore
     * matching must follow its contents instead.
     */
    private suspend fun snapshotTabsInDisplayOrder(
        manager: TerminalToolWindowTabsManager,
    ): List<TerminalToolWindowTab> = withContext(Dispatchers.EDT) {
        tabsInDisplayOrder(manager.tabs.toList())
    }

    private fun tabsInDisplayOrder(
        tabs: List<TerminalToolWindowTab>,
    ): List<TerminalToolWindowTab> {
        val tabByContent = IdentityHashMap<Content, TerminalToolWindowTab>()
        tabs.forEach { tabByContent[it.content] = it }

        val visitedManagers = java.util.Collections.newSetFromMap(IdentityHashMap<ContentManager, Boolean>())
        val addedContents = java.util.Collections.newSetFromMap(IdentityHashMap<Content, Boolean>())
        val orderedTabs = ArrayList<TerminalToolWindowTab>(tabs.size)
        for (tab in tabs) {
            val contentManager = tab.content.manager ?: continue
            if (!visitedManagers.add(contentManager)) continue
            for (content in contentManager.contents) {
                val orderedTab = tabByContent[content] ?: continue
                if (addedContents.add(content)) orderedTabs.add(orderedTab)
            }
        }

        // Detached or concurrently moving tabs may briefly have no content manager. Preserve the
        // terminal manager's stable order for those entries rather than dropping them.
        tabs.filterTo(orderedTabs) { addedContents.add(it.content) }
        return orderedTabs
    }

    private fun stateChanged() {
        stateModificationCount.incrementAndGet()
    }

    private suspend fun snapshotRestoreTabs(tabs: List<TerminalToolWindowTab>): List<RestoreTab> =
        withContext(Dispatchers.EDT) {
            tabs.map { tab ->
                val requestedDirectory = TerminalCompat.requestedDirectory(tab)
                val currentDirectory = TerminalCompat.currentDirectory(tab.view)
                RestoreTab(
                    tab = tab,
                    title = SessionMetadata.cleanTitle(tab.view.title.buildTitle()),
                    directories = listOfNotNull(currentDirectory, requestedDirectory).distinct(),
                    command = TerminalCompat.requestedCommand(tab),
                    idleShell = TerminalCompat.requestedProcessType(tab).let {
                        it == null || it == TerminalProcessType.SHELL
                    } &&
                        runCatching { !tab.view.hasChildProcesses() }.getOrDefault(false),
                )
            }
        }

    private suspend fun bindRestoredTab(
        candidate: RestoreTab,
        record: PersistedCodexSession,
        sendCommand: Boolean,
    ) {
        try {
            val title = SessionMetadata.cleanTitle(record.title).ifBlank { record.id.take(8) }
            decorate(candidate.tab, title)
            rememberBinding(candidate.tab.content, record.id)
            if (sendCommand) sendResume(candidate.tab, record)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            tabSessions.remove(candidate.tab.content, record.id)
            LOG.warn("Unable to restore Codex session ${record.id}", error)
        }
    }

    private suspend fun sendResume(tab: TerminalToolWindowTab, record: PersistedCodexSession) {
        val selector = currentResumeSelector(record)
        val command = CodexLaunchArguments.shellResumeCommand(
            "codex",
            selector,
            record.resumeArgs,
        )
        withContext(Dispatchers.EDT) {
            tab.view.createSendTextBuilder()
                .useBracketedPasteMode()
                .shouldExecute()
                .send(command)
        }
    }

    private suspend fun currentResumeSelector(record: PersistedCodexSession): String {
        val current = withContext(Dispatchers.IO) {
            runCatching { SessionMetadata.read(Path.of(record.rolloutPath)) }
                .getOrNull()
                ?.takeIf { it.id == record.id }
                ?.resumeSelector
                .orEmpty()
        }
        synchronized(this) {
            state.sessions.firstOrNull { it.id == record.id }?.let { saved ->
                if (saved.resumeSelector != current) {
                    saved.resumeSelector = current
                    saved.updatedAtMillis = System.currentTimeMillis()
                    stateChanged()
                }
            }
        }
        return current.ifBlank { record.id }
    }

    private fun sameDirectory(actual: String?, expected: String): Boolean {
        return directoryKey(actual) != null && directoryKey(actual) == directoryKey(expected)
    }

    private fun isGenericTerminalTitle(title: String): Boolean {
        return title.isBlank() || title == "Local" || (title.startsWith("Local (") && title.endsWith(')'))
    }

    private fun directoryKey(directory: String?): Path? {
        if (directory.isNullOrBlank()) return null
        return runCatching {
            val path = Path.of(directory)
            if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()
        }.getOrNull()
    }

    override fun dispose() {
        closing.set(true)
        tabActivity.values.forEach { it.disposed.set(true) }
        tabActivity.clear()
    }

    private data class RestoreTab(
        val tab: TerminalToolWindowTab,
        val title: String,
        val directories: List<String>,
        val command: List<String>,
        val idleShell: Boolean,
    )

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
        const val RESTORE_SETTLE_NANOS = 10_000_000_000L
    }
}
