package com.github.ryantateamp.codexsessiontabs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name

internal object SessionMetadata {
    private val json = Json { ignoreUnknownKeys = true }
    // Codex currently emits UUIDv7 identifiers, so do not constrain the version nibble
    // to the older UUIDv1-v5 range.
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    private val unwantedPrefix = Regex("^(?:Codi|Codex):\\s*", RegexOption.IGNORE_CASE)
    private val indexCache = ConcurrentHashMap<Path, SessionIndexSnapshot>()

    fun read(rollout: Path): DiscoveredCodexSession? {
        val meta = readSessionMeta(rollout)
        val id = meta.first.ifBlank { uuid.find(rollout.name)?.value.orEmpty() }
        if (id.isBlank()) return null

        val codexHome = codexHomeFor(rollout)
        val sessionIndex = codexHome?.resolve("session_index.jsonl")
        val rawTitle = sessionIndex?.let { readThreadName(it, id) }.orEmpty()
        val title = cleanTitle(rawTitle)
        val resumeSelector = sessionIndex?.let { readUniqueThreadName(it, id) }.orEmpty()

        val cwd = meta.second
        val fallback = Path.of(cwd.ifBlank { "." }).fileName?.toString().orEmpty()
        return DiscoveredCodexSession(
            id = id,
            title = title.ifBlank { fallback.ifBlank { id.take(8) } },
            cwd = cwd,
            rolloutPath = rollout.toString(),
            resumeSelector = resumeSelector,
        )
    }

    internal fun cleanTitle(value: String): String = value.trim().replace(unwantedPrefix, "").trim()

    internal fun codexHomeFor(rollout: Path): Path? {
        var current: Path? = rollout.parent
        while (current != null) {
            if (current.fileName?.toString() == "sessions") return current.parent
            current = current.parent
        }
        return null
    }

    internal fun readThreadName(index: Path, sessionId: String): String? = readThreadNames(index)?.get(sessionId)

    internal fun readUniqueThreadName(index: Path, sessionId: String): String? {
        val threadNames = readThreadNames(index) ?: return null
        val candidate = threadNames[sessionId]?.takeIf(String::isNotBlank) ?: return null
        return candidate.takeIf { name -> threadNames.values.count { it == name } == 1 }
    }

    private fun readThreadNames(index: Path): Map<String, String>? {
        val cacheKey = index.toAbsolutePath().normalize()
        val attributes = runCatching {
            Files.readAttributes(cacheKey, BasicFileAttributes::class.java)
        }.getOrNull()
        if (attributes == null || !attributes.isRegularFile || attributes.size() > MAX_INDEX_BYTES) {
            indexCache.remove(cacheKey)
            return null
        }

        val cached = indexCache[cacheKey]
        if (cached != null &&
            cached.modifiedAt == attributes.lastModifiedTime() &&
            cached.size == attributes.size()
        ) {
            return cached.threadNames
        }

        val threadNames = mutableMapOf<String, String>()
        Files.newBufferedReader(cacheKey).useLines { lines ->
            lines.forEach { line ->
                runCatching {
                    val objectValue = json.parseToJsonElement(line).jsonObject
                    val id = objectValue["id"]?.jsonPrimitive?.contentOrNull
                    if (id != null) {
                        val threadName = objectValue["thread_name"]?.jsonPrimitive?.contentOrNull
                        if (threadName == null) threadNames.remove(id) else threadNames[id] = threadName
                    }
                }
            }
        }
        indexCache[cacheKey] = SessionIndexSnapshot(
            modifiedAt = attributes.lastModifiedTime(),
            size = attributes.size(),
            threadNames = threadNames,
        )
        return threadNames
    }

    private fun readSessionMeta(rollout: Path): Pair<String, String> {
        if (!Files.isRegularFile(rollout)) return "" to ""

        Files.newBufferedReader(rollout).use { reader ->
            repeat(MAX_META_LINES) {
                val line = reader.readLine() ?: return "" to ""
                val parsed = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@repeat
                if (parsed["type"]?.jsonPrimitive?.contentOrNull != "session_meta") return@repeat

                val payload = parsed["payload"]?.jsonObject ?: return@repeat
                return payload["id"]?.jsonPrimitive?.contentOrNull.orEmpty() to
                    payload["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }
        return "" to ""
    }

    private const val MAX_META_LINES = 40
    private const val MAX_INDEX_BYTES = 16L * 1024 * 1024

    private data class SessionIndexSnapshot(
        val modifiedAt: FileTime,
        val size: Long,
        val threadNames: Map<String, String>,
    )
}
