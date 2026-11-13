package jp.noppy.lumamusic.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import jp.noppy.lumamusic.core.readEmbeddedTags
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Times are wall-clock milliseconds; metadataMs sums the two worker durations. */
data class ScanMetrics(val enumerationMs: Long = 0, val metadataMs: Long = 0, val artworkMs: Long = 0, val dbMs: Long = 0, val firstPublishedMs: Long? = null, val totalMs: Long = 0, val parsed: Int = 0, val cached: Int = 0, val queries: Int = 0, val filteringMs: Long = 0)
data class ScanState(val running: Boolean = false, val count: Int = 0, val message: String? = null, val stage: String = "IDLE", val status: String = "IDLE", val metadataDone: Int = 0, val metrics: ScanMetrics = ScanMetrics())

class LibraryRepository(private val context: Context, private val db: LibraryDatabase) {
    val dao = db.library()
    val tracks = dao.tracks()
    val folders = dao.folders()
    val userTracks = dao.userTracks()
    val playlists = dao.playlists()
    val entries = dao.entries()
    private val scanMutex = Mutex()
    private val detailLocks = ConcurrentHashMap<String, Mutex>()
    private val detailSlots = Semaphore(2)
    private val mutableScan = MutableStateFlow(ScanState())
    val scan = mutableScan.asStateFlow()
    @Volatile private var scanJob: Job? = null
    private val extensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
    private fun lock(uri: String) = detailLocks.getOrPut(uri) { Mutex() }
    private data class Node(val uri: String, val name: String, val mime: String, val size: Long, val modified: Long)
    private data class Inventory(val folder: MusicFolder, val found: Set<String>, val error: String?, val sidecars: Set<String>)

    suspend fun addFolder(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val root = DocumentFile.fromTreeUri(context, uri) ?: error("フォルダーを開けません")
            dao.folder(MusicFolder(uri.toString(), root.name ?: "音楽フォルダー"))
            rescan()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableScan.value = ScanState(message = "フォルダー追加に失敗: ${e.localizedMessage}", status = "PARTIAL_FAILURE")
        }
    }

    fun cancelScan() { scanJob?.cancel(CancellationException("スキャンをキャンセルしました")) }
    suspend fun removeFolder(folder: MusicFolder) = withContext(Dispatchers.IO) {
        cancelScan()
        scanMutex.withLock {
            db.withTransaction { dao.deleteAnalysisForFolder(folder.uri); dao.clearRefs(folder.uri); dao.deleteFolder(folder.uri) }
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** Coalesces duplicate requests. Committed discovery batches survive cancellation. */
    suspend fun rescan(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!scanMutex.tryLock()) return@withContext
        val began = SystemClock.elapsedRealtime()
        val enumTime = AtomicLong(); val dbTime = AtomicLong(); val tagTime = AtomicLong(); val filterTime = AtomicLong(); val first = AtomicLong(-1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failedUris = ConcurrentHashMap.newKeySet<String>()
        var queryCount = 0; var parsed = 0; var cached = 0
        scanJob = currentCoroutineContext()[Job]
        mutableScan.value = ScanState(true, stage = "DISCOVERY", status = "RUNNING")
        try {
            val previous = dao.trackList().associateBy { it.uri }
            val discovered = mutableSetOf<String>()
            val inventory = mutableListOf<Inventory>()
            val pendingLyrics = mutableMapOf<String, String?>()
            coroutineScope {
                // Only light row objects queue here. Slow tag readers must never stall discovery.
                val work = Channel<Track>(Channel.UNLIMITED)
                val workers = List(2) {
                    launch(Dispatchers.IO) {
                        for (item in work) {
                            ensureActive()
                            val start = SystemClock.elapsedRealtime()
                            try {
                                lock(item.uri).withLock {
                                    val current = dao.track(item.uri) ?: return@withLock
                                    if (current.modified != item.modified || current.size != item.size) return@withLock
                                    val enriched = readBasic(current)
                                    val write = SystemClock.elapsedRealtime()
                                    dao.upsertTracks(listOf(enriched))
                                    dbTime.addAndGet(SystemClock.elapsedRealtime() - write)
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                failedUris.add(item.uri)
                                failures.add("${item.title}: ${e.localizedMessage}")
                                lock(item.uri).withLock {
                                    dao.track(item.uri)?.let { current -> if (current.modified == item.modified && current.size == item.size) dao.upsertTracks(listOf(current.copy(metadataState = "FAILED"))) }
                                }
                            } finally {
                                tagTime.addAndGet(SystemClock.elapsedRealtime() - start)
                                mutableScan.update { it.copy(metadataDone = it.metadataDone + 1) }
                            }
                        }
                    }
                }
                try {
                    for (folder in dao.folderList()) {
                        val found = mutableSetOf<String>()
                        val sidecars = mutableSetOf<String>()
                        var folderError: String? = null
                        try {
                            val tree = Uri.parse(folder.uri)
                            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                            val pending = ArrayDeque<Uri>().apply { add(root) }
                            val visited = mutableSetOf<String>()
                            while (pending.isNotEmpty()) {
                                ensureActive()
                                val directory = pending.removeFirst()
                                if (!visited.add(directory.toString())) continue
                                queryCount++
                                val lyrics = mutableMapOf<String, String>()
                                val audioNames = mutableMapOf<String, String>()
                                val batch = mutableListOf<Track>()
                                val refs = mutableListOf<TrackFolder>()
                                val parse = mutableListOf<Track>()
                                suspend fun publish() {
                                    if (refs.isEmpty()) return
                                    val write = SystemClock.elapsedRealtime()
                                    db.withTransaction { if (batch.isNotEmpty()) dao.upsertTracks(batch.toList()); dao.refs(refs.toList()) }
                                    dbTime.addAndGet(SystemClock.elapsedRealtime() - write)
                                    first.compareAndSet(-1, SystemClock.elapsedRealtime() - began)
                                    mutableScan.update { it.copy(count = discovered.size, stage = "DISCOVERY") }
                                    for (track in parse) work.send(track)
                                    batch.clear(); refs.clear(); parse.clear()
                                }
                                eachChild(directory, enumTime) { file ->
                                    ensureActive()
                                    // macOS AppleDouble metadata inherits the music extension but is not audio.
                                    if (file.name.startsWith("._")) { sidecars.add(file.uri); return@eachChild }
                                    val classification = SystemClock.elapsedRealtime()
                                    val isDirectory = file.mime == DocumentsContract.Document.MIME_TYPE_DIR
                                    val isAudio = !isDirectory && file.name.substringAfterLast('.', "").lowercase() in extensions
                                    filterTime.addAndGet(SystemClock.elapsedRealtime() - classification)
                                    if (isDirectory) { pending.add(Uri.parse(file.uri)); return@eachChild }
                                    val stem = file.name.substringBeforeLast('.').lowercase()
                                    if (file.name.endsWith(".lrc", true)) lyrics[stem] = file.uri
                                    if (!isAudio) return@eachChild
                                    audioNames[file.uri] = stem
                                    found.add(file.uri)
                                    refs.add(TrackFolder(file.uri, folder.uri))
                                    if (discovered.add(file.uri)) {
                                        val old = previous[file.uri]
                                        val unchanged = !force && old != null && old.metadataState == "READY" && file.modified > 0 && file.size > 0 && old.modified == file.modified && old.size == file.size
                                        if (unchanged) {
                                            cached++
                                        } else {
                                            val provisional = (old ?: Track(file.uri, file.name.substringBeforeLast('.'))).copy(
                                                modified = file.modified, size = file.size,
                                                mime = file.mime, sampleRate = null, bitDepth = null, channels = null,
                                                metadataState = "PENDING", detailState = "PENDING", artwork = null, artworkThumbnail = null, embeddedLyrics = null,
                                            )
                                            batch.add(provisional); parse.add(provisional); parsed++
                                        }
                                    }
                                    if (refs.size >= 16) publish()
                                }
                                publish()
                                // Opaque SAF document IDs cannot be guessed from a .lrc filename.
                                audioNames.forEach { (uri, stem) -> if (previous[uri]?.externalLyrics != lyrics[stem]) pendingLyrics[uri] = lyrics[stem] }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            folderError = if (e is SecurityException) "アクセス権限がありません。フォルダーを再登録してください" else e.localizedMessage ?: "フォルダーを読み取れません"
                            failures.add("${folder.name}: $folderError")
                        }
                        inventory.add(Inventory(folder, found, folderError, sidecars))
                    }
                } finally { work.close() }
                mutableScan.update { it.copy(stage = "METADATA") }
                workers.joinAll()
            }
            if (pendingLyrics.isNotEmpty()) {
                val started = SystemClock.elapsedRealtime()
                db.withTransaction { pendingLyrics.forEach { (uri, lyrics) -> dao.externalLyrics(uri, lyrics) } }
                dbTime.addAndGet(SystemClock.elapsedRealtime() - started)
            }
            // Remove references only after a complete traversal, and only after a second explicit missing-file check.
            for ((folder, found, enumerationError, sidecars) in inventory) {
                // Remove only library references to conclusively identified metadata files.
                // The source files and user-edited metadata are retained.
                if (sidecars.isNotEmpty()) db.withTransaction { sidecars.forEach { dao.removeRef(folder.uri, it) } }
                val partial = enumerationError ?: if (found.any { it in failedUris }) "一部のタグを読み取れませんでした。再試行できます" else null
                if (partial == null) {
                    val removed = dao.refsForFolder(folder.uri).filter { it.trackUri !in found }
                    val missing = removed.filter { confirmedMissing(it.trackUri) }
                    db.withTransaction { missing.forEach { dao.removeRef(folder.uri, it.trackUri) }; dao.folder(folder.copy(error = null)) }
                } else dao.folder(folder.copy(error = partial))
            }
        } catch (e: CancellationException) {
            mutableScan.update { it.copy(status = "CANCELLED", message = "スキャンをキャンセルしました。発見済みの曲は保持されています") }
            throw e
        } catch (e: Exception) {
            failures.add(e.localizedMessage ?: "スキャンに失敗しました。発見済みの曲は保持されています")
            Log.w("LumaScan", "Scan failed", e)
        } finally {
            val metrics = ScanMetrics(enumTime.get(), tagTime.get(), mutableScan.value.metrics.artworkMs, dbTime.get(), first.get().takeIf { it >= 0 }, SystemClock.elapsedRealtime() - began, parsed, cached, queryCount, filterTime.get())
            val cancelled = !currentCoroutineContext().isActive
            val messages = synchronized(failures) { failures.take(12).joinToString("\n").takeIf { it.isNotBlank() } }
            mutableScan.update { it.copy(running = false, stage = "IDLE", status = if (cancelled) "CANCELLED" else if (messages != null) "PARTIAL_FAILURE" else "COMPLETE", message = messages ?: it.message.takeIf { cancelled }, metrics = metrics) }
            Log.i("LumaScan", "scan=$metrics status=${mutableScan.value.status}")
            scanJob = null; scanMutex.unlock()
        }
    }

    private suspend fun eachChild(directory: Uri, timing: AtomicLong, visit: suspend (Node) -> Unit) {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(directory))
        val queryStarted = SystemClock.elapsedRealtime()
        val cursor = context.contentResolver.query(uri, columns, null, null, null) ?: error("フォルダー一覧を取得できません")
        timing.addAndGet(SystemClock.elapsedRealtime() - queryStarted)
        cursor.use {
            while (true) {
                val started = SystemClock.elapsedRealtime()
                if (!cursor.moveToNext()) { timing.addAndGet(SystemClock.elapsedRealtime() - started); break }
                val id = cursor.getString(0) ?: continue
                val node = Node(DocumentsContract.buildDocumentUriUsingTree(directory, id).toString(), cursor.getString(1) ?: id.substringAfterLast('/'), cursor.getString(2).orEmpty(), if (cursor.isNull(3)) 0 else cursor.getLong(3), if (cursor.isNull(4)) 0 else cursor.getLong(4))
                timing.addAndGet(SystemClock.elapsedRealtime() - started)
                visit(node)
            }
        }
    }
    private fun confirmedMissing(uri: String): Boolean = try {
        context.contentResolver.query(Uri.parse(uri), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { !it.moveToFirst() } ?: false
    } catch (_: java.io.FileNotFoundException) { true } catch (_: Exception) { false }

    private fun readBasic(track: Track): Track {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(track.uri))
            fun tag(key: Int) = retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }
            return track.copy(title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: track.title,
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "不明なアーティスト", album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "不明なアルバム",
                albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty(), genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(), year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty(),
                trackNumber = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull() ?: 0,
                discNumber = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.toIntOrNull() ?: 0,
                duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0, mime = tag(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: track.mime, metadataState = "READY")
        } finally { retriever.release() }
    }

    /** Stage C is called only for a visible/playing track or an explicit lyrics request. */
    suspend fun ensureDetails(uri: String): Track? = withContext(Dispatchers.IO) {
        detailSlots.withPermit { lock(uri).withLock {
            val track = dao.track(uri) ?: return@withLock null
            val cachedArtExists = listOfNotNull(track.artwork, track.artworkThumbnail).all { runCatching { File(Uri.parse(it).path.orEmpty()).exists() }.getOrDefault(false) }
            if ((track.detailState == "READY" && cachedArtExists) || track.detailState == "FAILED") return@withLock track
            val started = SystemClock.elapsedRealtime()
            var artworkElapsed = 0L
            try {
                val retriever = MediaMetadataRetriever()
                var art: String? = null; var thumbnail: String? = null
                try {
                    retriever.setDataSource(context, Uri.parse(uri))
                    retriever.embeddedPicture?.let { bytes ->
                        val key = UUID.nameUUIDFromBytes("$uri|${track.size}|${track.modified}".toByteArray()).toString()
                        val full = File(context.cacheDir, "art/$key.image"); full.parentFile?.mkdirs()
                        if (!full.exists()) full.writeBytes(bytes)
                        val thumb = File(context.cacheDir, "art/$key.jpg")
                        if (!thumb.exists()) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                            if (bitmap != null) try { val ratio = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height)); val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
                                try { thumb.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) } } finally { if (scaled !== bitmap) scaled.recycle() }
                            } finally { bitmap.recycle() }
                        }
                        art = Uri.fromFile(full).toString(); thumbnail = thumb.takeIf(File::exists)?.let { Uri.fromFile(it).toString() }
                    }
                } finally { retriever.release(); artworkElapsed = SystemClock.elapsedRealtime() - started }
                val tagInfo = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    val prefix = java.io.ByteArrayOutputStream(); val header = ByteArray(12); var length = 0
                    while (length < header.size) { val count = input.read(header, length, header.size - length); if (count <= 0) break; length += count }
                    val signature = String(header, 0, length, Charsets.ISO_8859_1)
                    if (!signature.startsWith("ID3") && !signature.startsWith("fLaC") && !signature.startsWith("OggS")) return@use readEmbeddedTags(byteArrayOf())
                    prefix.write(header, 0, length); val buffer = ByteArray(8192); var remaining = 2 * 1024 * 1024 - length
                    while (remaining > 0) { val count = input.read(buffer, 0, minOf(buffer.size, remaining)); if (count <= 0) break; prefix.write(buffer, 0, count); remaining -= count }
                    readEmbeddedTags(prefix.toByteArray())
                }
                var rate: Int? = null; var channels: Int? = null; var bits = tagInfo?.bitDepth; var mime: String? = null
                val extractor = MediaExtractor()
                try { extractor.setDataSource(context, Uri.parse(uri), null); for (i in 0 until extractor.trackCount) { val format = extractor.getTrackFormat(i); if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                    mime = format.getString(MediaFormat.KEY_MIME); if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }; if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 }
                    if (format.containsKey("bits-per-sample")) bits = format.getInteger("bits-per-sample").takeIf { it > 0 }
                    if (bits == null && mime == "audio/raw" && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) bits = when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) { 2 -> 16; 3 -> 8; 21 -> 24; 22, 4 -> 32; else -> null }; break
                } } catch (e: Exception) { if (e is CancellationException) throw e; Log.w("LumaScan", "Technical format unavailable for $uri", e) } finally { extractor.release() }
                dao.details(uri, track.modified, track.size, art, thumbnail, tagInfo?.lyrics, bits, rate, channels, mime, "READY")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("LumaScan", "Stage C failed for $uri", e)
                dao.details(uri, track.modified, track.size, track.artwork, track.artworkThumbnail, track.embeddedLyrics, track.bitDepth, track.sampleRate, track.channels, track.mime, "FAILED")
                mutableScan.update { it.copy(message = "詳細情報を取得できません: ${track.title}: ${e.localizedMessage}") }
            } finally { val elapsed = SystemClock.elapsedRealtime() - started; mutableScan.update { it.copy(metrics = it.metrics.copy(artworkMs = it.metrics.artworkMs + artworkElapsed)) }; Log.i("LumaScan", "stageC_ms=$elapsed artwork_ms=$artworkElapsed uri=$uri") }
            dao.track(uri)
        } }
    }
    suspend fun ensureArtwork(uri: String): Track? = ensureDetails(uri)

    suspend fun toggleFavorite(uri: String) = db.withTransaction { val previous = dao.userTrack(uri) ?: UserTrack(uri); dao.userTrack(previous.copy(favorite = !previous.favorite)) }
    suspend fun saveLyrics(uri: String, text: String) = db.withTransaction { val previous = dao.userTrack(uri) ?: UserTrack(uri); dao.userTrack(previous.copy(lyrics = text)) }
    suspend fun lyrics(track: Track): String = withContext(Dispatchers.IO) {
        dao.userTrack(track.uri)?.lyrics?.let { return@withContext it }
        val detailed = ensureDetails(track.uri) ?: track
        detailed.externalLyrics?.let { context.contentResolver.openInputStream(Uri.parse(it))?.bufferedReader()?.use { reader -> reader.readText() } } ?: detailed.embeddedLyrics.orEmpty()
    }
    suspend fun createPlaylist(name: String) { if (name.isNotBlank()) dao.playlist(MusicPlaylist(name = name.trim())) }
    suspend fun addToPlaylist(id: Long, uri: String) = db.withTransaction { dao.entries(listOf(PlaylistEntry(id, UUID.randomUUID().toString(), uri, dao.nextPosition(id)))) }
    suspend fun createPlaylistWithTrack(name: String, uri: String) = db.withTransaction {
        val title = name.trim()
        require(title.isNotEmpty())
        val id = dao.playlist(MusicPlaylist(name = title))
        dao.entries(listOf(PlaylistEntry(id, UUID.randomUUID().toString(), uri, 0)))
    }
    suspend fun savePlaylistEntries(id: Long, entries: List<PlaylistEntry>) = db.withTransaction { dao.clearPlaylist(id); dao.entries(entries.mapIndexed { i, entry -> entry.copy(position = i) }) }
}
