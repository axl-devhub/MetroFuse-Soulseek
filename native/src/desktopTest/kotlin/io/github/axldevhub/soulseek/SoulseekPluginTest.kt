package io.github.axldevhub.soulseek

import dev.metrofuse.api.PluginStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoulseekPluginTest {
    private class MemoryStorage : PluginStorage {
        val values = mutableMapOf<String, JsonElement>()

        override suspend fun get(key: String) = values[key]

        override suspend fun set(
            key: String,
            value: JsonElement?,
        ) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun hostTrackIdsMapToTheKeyResolveRequestsCarry() {
        assertEquals("dQw4w9WgXcQ", SoulseekPlugin.keyOf("metrofuse:metrofuse.youtube-music:dQw4w9WgXcQ"))
        assertEquals("abc:def", SoulseekPlugin.keyOf("metrofuse:soulseek:abc:def"))
        assertEquals("plain", SoulseekPlugin.keyOf("plain"))
    }

    @Test
    fun searchTrackIdsRoundTripPeerAndPath() {
        val id = SoulseekPlugin.encodeSearchTrack("peer name", "Música\\Artist\\01 Song.flac")
        assertEquals("peer name" to "Música\\Artist\\01 Song.flac", SoulseekPlugin.decodeSearchTrack(id))
        assertNull(SoulseekPlugin.decodeSearchTrack("dQw4w9WgXcQ"))
        assertNull(SoulseekPlugin.decodeSearchTrack("slsk-zz"))
        assertNull(SoulseekPlugin.decodeSearchTrack("slsk-6e6f6e65776c696e65"))
    }

    @Test
    fun downloadsSurviveARestartAndSkipDeletedFiles() {
        runBlocking {
            val dir = Files.createTempDirectory("soulseek-store").toFile()
            val storage = MemoryStorage()
            val kept = File(dir, "kept.flac").apply { writeText("audio") }
            val deleted = File(dir, "deleted.mp3").apply { writeText("audio") }
            val store = DownloadStore(storage, dir)
            store.put("a", entry(kept, "lossless", addedAt = 1))
            store.put("b", entry(deleted, "320", addedAt = 2))
            deleted.delete()

            val reloaded = DownloadStore(storage, dir).apply { load() }
            assertEquals(kept.absolutePath, reloaded.get("a")?.path)
            assertNull(reloaded.get("b"))
            assertEquals(listOf("a"), reloaded.entries().map { it.first })
            assertEquals("Song", reloaded.get("a")?.track("a")?.title)
            dir.deleteRecursively()
        }
    }

    private fun entry(
        file: File,
        format: String,
        addedAt: Long,
    ) = DownloadStore.Entry(file.absolutePath, "Song", "Artist", "Album", 200_000, "", format, file.extension, null, 24, 96_000, addedAt)
}
