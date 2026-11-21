package io.github.axldevhub.soulseek

import dev.metrofuse.api.PluginStorage
import dev.metrofuse.api.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloaded Soulseek files by the song key resolve requests use, kept in the plugin's storage so they survive restarts.
 * Entries whose file was deleted are ignored.
 */
class DownloadStore(
    private val storage: PluginStorage,
    val directory: File,
) {
    data class Entry(
        val path: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val artwork: String,
        val format: String,
        val extension: String,
        val bitrate: Int?,
        val bitDepth: Int?,
        val sampleRate: Int?,
        val addedAt: Long = System.currentTimeMillis(),
    ) {
        fun track(key: String) = Track(key, title, artist, album, durationMs, artwork, mapOf("soulseekFormat" to format))
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val saveLock = Mutex()

    suspend fun load() {
        val saved = storage.get(STORAGE_KEY) as? JsonObject ?: return
        saved.forEach { (key, value) -> (value as? JsonObject)?.let(::decode)?.let { entries[key] = it } }
    }

    fun get(key: String): Entry? = entries[key]?.takeIf { File(it.path).isFile }

    /** Entries with their file still on disk, newest first. */
    fun entries(): List<Pair<String, Entry>> =
        entries
            .filterValues { File(it.path).isFile }
            .toList()
            .sortedByDescending { it.second.addedAt }

    suspend fun put(
        key: String,
        entry: Entry,
    ) {
        entries[key] = entry
        saveLock.withLock {
            storage.set(STORAGE_KEY, JsonObject(entries.mapValues { encode(it.value) }))
        }
    }

    private fun encode(entry: Entry) =
        buildJsonObject {
            put("path", entry.path)
            put("title", entry.title)
            put("artist", entry.artist)
            put("album", entry.album)
            put("durationMs", entry.durationMs)
            put("artwork", entry.artwork)
            put("format", entry.format)
            put("extension", entry.extension)
            entry.bitrate?.let { put("bitrate", it) }
            entry.bitDepth?.let { put("bitDepth", it) }
            entry.sampleRate?.let { put("sampleRate", it) }
            put("addedAt", entry.addedAt)
        }

    private fun decode(json: JsonObject): Entry? {
        fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
        return Entry(
            path = text("path") ?: return null,
            title = text("title").orEmpty(),
            artist = text("artist").orEmpty(),
            album = text("album").orEmpty(),
            durationMs = json["durationMs"]?.jsonPrimitive?.longOrNull ?: 0,
            artwork = text("artwork").orEmpty(),
            format = text("format").orEmpty(),
            extension = text("extension").orEmpty(),
            bitrate = json["bitrate"]?.jsonPrimitive?.intOrNull,
            bitDepth = json["bitDepth"]?.jsonPrimitive?.intOrNull,
            sampleRate = json["sampleRate"]?.jsonPrimitive?.intOrNull,
            addedAt = json["addedAt"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    private companion object {
        const val STORAGE_KEY = "downloads"
    }
}
