package io.github.axldevhub.soulseek

import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HomePlugin
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.LoginCredentials
import dev.metrofuse.api.LoginField
import dev.metrofuse.api.LoginPlugin
import dev.metrofuse.api.LoginRequest
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.PluginLoginState
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays songs from Soulseek. The audio source answers with a downloaded file when one exists for the song, and starts a
 * Soulseek download for queued songs; prefetch downloads upcoming songs so they are ready when they play.
 */
class SoulseekPlugin :
    NativePlugin,
    LoginPlugin,
    HomePlugin {
    private lateinit var context: PluginContext
    private lateinit var store: DownloadStore

    private val state = MutableStateFlow(PluginLoginState())
    override val loginState: StateFlow<PluginLoginState> get() = state

    // Credentials stay in memory; the host keeps the encrypted copy and restores it through login().
    private var credentials: Pair<String, String>? = null
    private var client: SoulseekClient? = null
    private val clientLock = Mutex()

    private val jobs = ConcurrentHashMap<String, Deferred<DownloadStore.Entry?>>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val downloads = Semaphore(2)

    override suspend fun start(context: PluginContext) {
        this.context = context
        store = DownloadStore(context.storage, downloadDirectory())
        store.load()
        context.registerAudioSource(audio)
        context.registerSearchProvider(search)
        // macOS and Linux MetroFuse has no encrypted credential store, so its sign-in form is unavailable there; the
        // plugin's settings take the password once and the system keychain keeps it.
        context.scope.launch(Dispatchers.IO) {
            val username = settingText(USERNAME)
            if (DesktopKeychain.available && username.isNotEmpty() && credentials == null) {
                DesktopKeychain.read(username)?.let { password ->
                    credentials = username to password
                    state.value = PluginLoginState(loggedIn = true, accountName = username)
                }
            }
            context.settings.values
                .map { (it[USERNAME] as? JsonPrimitive)?.contentOrNull.orEmpty().trim() to (it[PASSWORD] as? JsonPrimitive)?.contentOrNull.orEmpty() }
                .distinctUntilChanged()
                .collect { (user, password) -> if (password.isNotEmpty()) signInFromSettings(user, password) }
        }
        context.scope.launch {
            // The current song and the next two, by the key resolve requests use.
            context.player.state
                .map { playback ->
                    val index = playback.currentIndex ?: playback.queue.indexOfFirst { it.id == playback.track.id }
                    (listOf(playback.track) + playback.queue.drop(index + 1).take(2)).filter { it.title.isNotBlank() }
                }.distinctUntilChanged()
                .collect { tracks -> if (setting(PREFETCH, true)) tracks.forEach(::prefetch) }
        }
    }

    override suspend fun stop() {
        dropClient()
    }

    // Sign-in

    override suspend fun loginRequest() =
        LoginRequest(
            fields = listOf(LoginField("username", "Username", secret = false), LoginField("password", "Password", secret = true)),
            instructions =
                "Soulseek checks these before they are saved. An unused username creates a new account. " +
                    "Soulseek allows one login per account, so close other Soulseek clients using it.",
        )

    override suspend fun login(credentials: LoginCredentials): PluginLoginState {
        val username = credentials.values["username"].orEmpty().trim()
        val password = credentials.values["password"].orEmpty()
        require(username.isNotEmpty() && password.isNotEmpty()) { "Enter a username and password" }
        val candidate = SoulseekClient(username, password)
        try {
            candidate.login()
        } catch (e: SoulseekLoginException) {
            candidate.close()
            throw IllegalArgumentException("Soulseek rejected the login (${e.reason})")
        } catch (e: Exception) {
            candidate.close()
            throw e
        }
        clientLock.withLock {
            client?.close()
            client = candidate
        }
        this.credentials = username to password
        return PluginLoginState(loggedIn = true, accountName = username).also { state.value = it }
    }

    override suspend fun logout() {
        credentials?.first?.let { if (DesktopKeychain.available) withContext(Dispatchers.IO) { DesktopKeychain.delete(it) } }
        credentials = null
        dropClient()
        state.value = PluginLoginState()
    }

    /** Signs in with the settings' password, keeps it in the system keychain, and always clears the setting. */
    private suspend fun signInFromSettings(
        username: String,
        password: String,
    ) {
        try {
            if (username.isEmpty()) {
                context.log("Soulseek: enter a username before the password")
                return
            }
            login(LoginCredentials(mapOf("username" to username, "password" to password)))
            val saved = DesktopKeychain.available && withContext(Dispatchers.IO) { DesktopKeychain.save(username, password) }
            context.log(if (saved) "Soulseek: signed in; password saved in the system keychain" else "Soulseek: signed in for this session")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.log("Soulseek sign-in failed: ${e.message}")
        } finally {
            withContext(NonCancellable) { context.settings.set(PASSWORD, JsonPrimitive("")) }
        }
    }

    private suspend fun session(): SoulseekClient =
        clientLock.withLock {
            client?.let { return it }
            val (username, password) = credentials ?: throw IllegalStateException("Not signed in to Soulseek")
            SoulseekClient(username, password).also {
                try {
                    it.login()
                } catch (e: Exception) {
                    it.close()
                    throw e
                }
                client = it
            }
        }

    private suspend fun dropClient() =
        clientLock.withLock {
            client?.close()
            client = null
        }

    // Home

    override suspend fun getHome(request: HomeRequest): List<HomeShelf> {
        val entries = store.entries()
        if (entries.isEmpty()) return emptyList()
        return listOf(
            HomeShelf(
                title = "Downloaded from Soulseek",
                items = entries.map { (key, entry) -> HomeItem(track = entry.track(key)) },
                layout = HomeLayout.ROWS_GRID,
            ),
        )
    }

    // Audio

    private val audio =
        object : AudioSource {
            override val id = PROVIDER_ID
            override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT))

            override suspend fun resolve(request: AudioQuery): AudioResult {
                val key = request.identity.canonicalId
                store.get(key)?.let { return stream(it) }
                if (credentials == null) return error("NOT_SIGNED_IN", "Sign in to Soulseek in its account screen")
                decodeSearchTrack(key)?.let { (peer, filename) ->
                    // A track picked from Soulseek search has no other source, so it is worth the wait.
                    val entry = withTimeoutOrNull(WAIT_MS) { fetchFile(key, peer, filename, searchTrack(key, peer, filename, null)) }
                    return entry?.let(::stream) ?: error("NOT_READY", "$peer did not send the file in time")
                }
                val job = jobs[key] ?: queuedTrack(request.identity)?.let { prefetch(it) } ?: return error("NOT_FOUND", "")
                // By default another source plays this time and the Soulseek file is used from the next play on.
                val entry = if (setting(WAIT_FOR_DOWNLOAD, false)) withTimeoutOrNull(WAIT_MS) { job.await() } else job.finishedValue()
                return entry?.let(::stream) ?: error("DOWNLOADING", "Soulseek is still downloading this song")
            }
        }

    private fun stream(entry: DownloadStore.Entry): AudioResult {
        val file = File(entry.path)
        return AudioResult(
            stream =
                StreamDescriptor(
                    transport = StreamTransport.DIRECT,
                    locator = file.toURI().toString(),
                    mimeType = MIME_TYPES[entry.extension],
                    bitrate = entry.bitrate?.times(1000),
                    sampleRate = entry.sampleRate,
                    contentLength = file.length(),
                    durationSeconds = entry.durationMs.takeIf { it > 0 }?.div(1000),
                ),
        )
    }

    private fun error(
        code: String,
        message: String,
    ) = AudioResult(error = ProviderError(code, message, retryable = false))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun Deferred<DownloadStore.Entry?>.finishedValue() = if (isCompleted && !isCancelled) getCompleted() else null

    /** The queued or playing track a resolve request refers to: host ids are `metrofuse:<owner>:<id>`. */
    private fun queuedTrack(identity: TrackIdentity): Track? {
        val playback = context.player.state.value
        return (listOf(playback.track) + playback.queue).firstOrNull { keyOf(it.id) == identity.canonicalId }
    }

    /** Starts (or joins) the background search and download for a song from another source. */
    private fun prefetch(track: Track): Deferred<DownloadStore.Entry?>? {
        val key = keyOf(track.id)
        if (key.startsWith(SEARCH_PREFIX) || store.get(key) != null || credentials == null) return null
        if ((failedAt[key] ?: 0L) > System.currentTimeMillis() - RETRY_AFTER_MS) return null
        jobs[key]?.let { return it }
        val job =
            context.scope.async(start = CoroutineStart.LAZY) {
                try {
                    downloads.withPermit { downloadBestMatch(key, track) }
                } finally {
                    jobs.remove(key)
                }
            }
        return (jobs.putIfAbsent(key, job) ?: job.also { it.start() })
    }

    private suspend fun downloadBestMatch(
        key: String,
        track: Track,
    ): DownloadStore.Entry? {
        val artist = track.artist.substringBefore(", ")
        try {
            val replies = session().search(SoulseekMatching.searchQuery(artist, track.title), waitMs = SEARCH_WAIT_MS)
            val options =
                SoulseekMatching
                    .options(replies, artist, track.title, (track.duration / 1000).toInt().takeIf { it > 0 })
                    .sortedBy { preferredFormats().indexOf(it.format) }
            for (option in options.take(MAX_CANDIDATES)) {
                try {
                    return fetchFile(key, option.username, option.filename, track, option)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SoulseekTransferException) {
                    context.log("Soulseek: ${option.username} did not send a file: ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            dropClient()
            context.log("Soulseek connection failed: ${e.message}")
        } catch (e: Exception) {
            context.log("Soulseek download failed: ${e::class.simpleName}")
        }
        failedAt[key] = System.currentTimeMillis()
        return null
    }

    private suspend fun fetchFile(
        key: String,
        peer: String,
        filename: String,
        track: Track,
        option: SoulseekCandidate? = null,
    ): DownloadStore.Entry {
        val file = session().download(peer, filename, store.directory, startTimeoutMs = PEER_START_TIMEOUT_MS)
        val entry =
            DownloadStore.Entry(
                path = file.absolutePath,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.duration,
                artwork = track.artwork,
                format = option?.format ?: SoulseekMatching.formatOf(SoulseekFileResult(filename, file.length(), file.extension, emptyMap())) ?: "",
                extension = file.extension.lowercase(),
                bitrate = option?.bitrate,
                bitDepth = option?.bitDepth,
                sampleRate = option?.sampleRate,
            )
        store.put(key, entry)
        return entry
    }

    // Search

    private val search =
        object : SearchProvider {
            override val id = PROVIDER_ID

            override suspend fun search(request: SearchRequest): SearchResult {
                if (credentials == null || request.query.isBlank()) return SearchResult(emptyList())
                val replies =
                    try {
                        session().search(request.query, waitMs = SEARCH_WAIT_MS)
                    } catch (e: IOException) {
                        dropClient()
                        return SearchResult(emptyList())
                    }
                val formats = preferredFormats()
                val tracks =
                    replies
                        .flatMap { reply -> reply.files.mapNotNull { file -> SoulseekMatching.formatOf(file)?.let { Triple(reply, file, it) } } }
                        .sortedWith(compareBy({ formats.indexOf(it.third) }, { !it.first.hasFreeSlots }, { it.first.queueLength }))
                        .take(request.limit)
                        .map { (reply, file, _) -> searchTrack(encodeSearchTrack(reply.username, file.filename), reply.username, file.filename, file) }
                return SearchResult(tracks)
            }
        }

    /** A track for a Soulseek file, titled from its path: `Artist/Album/01 Title.flac`. */
    private fun searchTrack(
        id: String,
        peer: String,
        filename: String,
        file: SoulseekFileResult?,
    ): Track {
        val parts = filename.replace('\\', '/').split('/').filter { it.isNotBlank() }
        val title = parts.last().substringBeforeLast('.').replace(Regex("""^\d+[\s.\-_]+"""), "").trim()
        val album = parts.getOrNull(parts.size - 2).orEmpty()
        val artist = parts.getOrNull(parts.size - 3) ?: album.substringBefore(" - ")
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = (file?.attributes?.get(Codes.ATTR_DURATION) ?: 0) * 1000L,
            extras = mapOf("soulseekPeer" to peer),
        )
    }

    // Settings

    private fun setting(
        key: String,
        default: Boolean,
    ) = (context.settings.values.value[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private fun settingText(key: String) = (context.settings.values.value[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

    /**
     * Format order for the quality setting. CD-quality lossless leads by default: hi-res files are several times larger
     * for no audible gain on most playback, so they download slowly.
     */
    private fun preferredFormats(): List<String> =
        when ((context.settings.values.value[QUALITY] as? JsonPrimitive)?.contentOrNull) {
            QUALITY_320_FIRST -> listOf("320", "lossless", "hires")
            QUALITY_HIRES_FIRST -> listOf("hires", "lossless", "320")
            else -> listOf("lossless", "hires", "320")
        }

    private fun downloadDirectory(): File {
        // shortcut: the plugin API has no data folder; on Android java.io.tmpdir is the app cache folder, so its sibling
        // files/ survives cache clearing. Switch when MetroFuse exposes a plugin storage directory.
        val android = System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)
        return if (android) {
            File(File(System.getProperty("java.io.tmpdir")).parentFile, "files/soulseek")
        } else {
            File(System.getProperty("user.home"), "Music/Soulseek")
        }
    }

    companion object {
        const val PROVIDER_ID = "soulseek"
        private const val SEARCH_PREFIX = "slsk-"
        private const val PREFETCH = "prefetch"
        private const val WAIT_FOR_DOWNLOAD = "waitForDownload"
        private const val QUALITY = "quality"
        private const val USERNAME = "username"
        private const val PASSWORD = "password"
        private const val QUALITY_320_FIRST = "320 kbps first"
        private const val QUALITY_HIRES_FIRST = "Hi-res first"

        // A sharer that has not started sending by then is skipped for the next candidate.
        private const val PEER_START_TIMEOUT_MS = 25_000L
        private const val MAX_CANDIDATES = 5

        // MetroFuse gives searches 10 s and audio resolution 30 s.
        private const val SEARCH_WAIT_MS = 7_000L
        private const val WAIT_MS = 25_000L
        private const val RETRY_AFTER_MS = 30 * 60_000L

        private val MIME_TYPES =
            mapOf(
                "flac" to "audio/flac",
                "mp3" to "audio/mpeg",
                "m4a" to "audio/mp4",
                "aac" to "audio/aac",
                "ogg" to "audio/ogg",
                "opus" to "audio/ogg",
                "wav" to "audio/wav",
                "aiff" to "audio/aiff",
                "aif" to "audio/aiff",
            )

        /** The id a resolve request carries for a host track id: `metrofuse:<owner>:<id>` becomes `<id>`. */
        fun keyOf(hostId: String): String {
            val namespace = hostId.removePrefix("metrofuse:")
            if (namespace == hostId) return hostId
            return namespace.substringAfter(':', missingDelimiterValue = namespace)
        }

        // Hex rather than java.util.Base64, which needs Android 8; the plugin supports Android 7.
        fun encodeSearchTrack(
            peer: String,
            filename: String,
        ): String = SEARCH_PREFIX + "$peer\n$filename".toByteArray().joinToString("") { "%02x".format(it) }

        fun decodeSearchTrack(id: String): Pair<String, String>? {
            if (!id.startsWith(SEARCH_PREFIX)) return null
            val hex = id.removePrefix(SEARCH_PREFIX)
            if (hex.length % 2 != 0 || hex.any { it !in "0123456789abcdef" }) return null
            val text = String(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
            val peer = text.substringBefore('\n')
            val filename = text.substringAfter('\n', missingDelimiterValue = "")
            return if (peer.isEmpty() || filename.isEmpty()) null else peer to filename
        }
    }
}
