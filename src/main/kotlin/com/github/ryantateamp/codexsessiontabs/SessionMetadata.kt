package com.github.ryantateamp.codexsessiontabs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal object SessionMetadata {
    private val json = Json { ignoreUnknownKeys = true }
    // Codex currently emits UUIDv7 identifiers, so do not constrain the version nibble
    // to the older UUIDv1-v5 range.
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    private val unwantedPrefix = Regex("^(?:Codi|Codex):\\s*", RegexOption.IGNORE_CASE)

    fun read(rollout: Path): DiscoveredCodexSession? {
        val meta = readSessionMeta(rollout)
        val id = meta.first.ifBlank { uuid.find(rollout.name)?.value.orEmpty() }
        if (id.isBlank()) return null

        val codexHome = codexHomeFor(rollout)
        val title = codexHome
            ?.resolve("session_index.jsonl")
            ?.let { readThreadName(it, id) }
            .orEmpty()
            .let(::cleanTitle)

        val cwd = meta.second
        val fallback = Path.of(cwd.ifBlank { "." }).fileName?.toString().orEmpty()
        return DiscoveredCodexSession(
            id = id,
            title = title.ifBlank { fallback.ifBlank { id.take(8) } },
            cwd = cwd,
            rolloutPath = rollout.toString(),
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

    internal fun readThreadName(index: Path, sessionId: String): String? {
        if (!Files.isRegularFile(index) || Files.size(index) > MAX_INDEX_BYTES) return null

        var result: String? = null
        Files.newBufferedReader(index).useLines { lines ->
            lines.forEach { line ->
                runCatching {
                    val objectValue = json.parseToJsonElement(line).jsonObject
                    val id = objectValue["id"]?.jsonPrimitive?.contentOrNull
                    if (id == sessionId) {
                        result = objectValue["thread_name"]?.jsonPrimitive?.contentOrNull
                    }
                }
            }
        }
        return result
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
}
