package com.googletv.screensaver.playback

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import com.googletv.screensaver.MediaType
import com.googletv.screensaver.PhotoLabelMode
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.EnumSet
import kotlin.random.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class SmbSourceConfig(
    val host: String,
    val share: String,
    val rootPath: String,
)

/** A video known to the catalog; [path] is relative to the library's `originals` folder. */
data class VideoItem(val path: String, val durationSeconds: Double, val sizeBytes: Long) {
    /** Average bitrate in megabits per second, from file size and duration. */
    val bitrateMbps: Double get() = sizeBytes * 8.0 / durationSeconds / 1_000_000.0
}

/** [paths] are the album's photos (as in catalog v1); [videos] are its videos. */
data class AlbumEntry(
    val id: String,
    val title: String,
    val yearMonth: String?,
    val paths: List<String>,
    val videos: List<VideoItem> = emptyList(),
) {
    val photoCount: Int get() = paths.size
    val videoCount: Int get() = videos.size
}

data class AlbumIndex(
    val albums: List<AlbumEntry>,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** Newest modification time of the Photos database files the catalog was built from. */
    val sourceModifiedMillis: Long? = null,
    /** Every active, non-hidden video in the library, whether or not it is in an album. */
    val videos: List<VideoItem> = emptyList(),
    /** Photos the Photos app classified as screenshots, documents, or receipts; relative to `originals`. */
    val excludedPaths: Set<String> = emptySet(),
    /** Active photos whose displayed aspect is between 0.56 and 0.9, so two of them can share a slide. Unlisted photos are shown alone. */
    val portraitPaths: Set<String> = emptySet(),
)
data class AlbumCatalogProgress(val message: String, val bytesCopied: Long? = null, val totalBytes: Long? = null)
class AlbumIndexException(message: String) : IllegalStateException(message)

/** Read-only SMB Guest source. No write-capable file handles are opened. */
class SmbGuestPhotoSource(
    config: SmbSourceConfig,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val poolSize: Int = DEFAULT_POOL_SIZE,
    private val labelMode: PhotoLabelMode = PhotoLabelMode.default,
    private val placeNameLookup: ((Double, Double) -> String?)? = null,
    private val fallbackCacheDirectory: java.io.File,
    private val catalogCacheDirectory: java.io.File = fallbackCacheDirectory,
    private val databaseStageDirectory: java.io.File = java.io.File(catalogCacheDirectory, "photos-db-stage"),
    private val selectedAlbumIds: Set<String> = emptySet(),
    private val selectedAlbumMode: Boolean = selectedAlbumIds.isNotEmpty(),
    /** What the in-app slideshow plays. The screensaver always passes [MediaType.PHOTOS]. */
    private val mediaType: MediaType = MediaType.PHOTOS,
) : PhotoSource {
    private val validatedConfig = config.validated()
    private val host = validatedConfig.host
    private val shareName = validatedConfig.share
    private val rootPath = validatedConfig.rootPath
    private val videoCacheDirectory = java.io.File(fallbackCacheDirectory, "video-cache")
    private val connectionLock = Any()
    private var connection: SmbConnection? = null
    private val epoch = AtomicInteger()

    init {
        require(poolSize > 0)
    }

    override fun loadSlides(): List<Slide> {
        if (selectedAlbumMode) return loadSelectedAlbumSlides()
        if (!mediaType.includesVideos) return walkPhotoSlides(poolSize, savedExclusion()).first
        return loadLibrarySlidesWithVideos()
    }

    /**
     * Screenshots, documents, and receipts from the saved catalog. Without a usable catalog (never
     * built, or an older version waiting for a refresh) nothing is filtered; the log says so.
     */
    private fun savedExclusion(): PathSet {
        val index = PhotosAlbumCatalog.read(java.io.File(catalogCacheDirectory, ALBUM_CATALOG_FILE))
        if (index == null) {
            Log.w(LOG_TAG, "No usable album catalog: screenshots, documents, and receipts are not filtered until it is refreshed")
            return PathSet.NONE
        }
        notePortraits(index)
        return PathSet(index.excludedPaths)
    }

    // The catalog's portrait list, kept for isPairable. Replaced on every listing, read on the player's worker thread.
    @Volatile private var portraitPaths = PathSet.NONE

    private fun notePortraits(index: AlbumIndex) {
        portraitPaths = PathSet(index.portraitPaths)
    }

    override fun isPairable(slide: Slide): Boolean =
        slide.kind == SlideKind.PHOTO && portraitPaths.containsSmbPath(rootPath, slide.id)

    /** All photos plus videos from the catalog, or only videos, in proportion to the library's mix. */
    private fun loadLibrarySlidesWithVideos(): List<Slide> {
        ProgressiveReader.purgeStale(videoCacheDirectory)
        val index = loadAlbumIndex(java.io.File(catalogCacheDirectory, ALBUM_CATALOG_FILE))
        notePortraits(index)
        val libraryVideos = index.videos
        if (mediaType == MediaType.VIDEOS) {
            val picked = SlideMixer.pickVideos(libraryVideos, poolSize)
            if (picked.isEmpty()) {
                throw AlbumIndexException("No playable videos were found in the library. Refresh the Mac index or set Type to Photos.")
            }
            return picked.map(::videoSlide)
        }
        // The walk comes first: the library's photo count decides how many slots videos get.
        val (photos, photoCount) = walkPhotoSlides(poolSize, PathSet(index.excludedPaths))
        val videoCount = SlideMixer.videoSlots(poolSize, libraryVideos.size, photoCount)
        val videos = SlideMixer.pickVideos(libraryVideos, videoCount).map(::videoSlide)
        return SlideMixer.shuffleTogether(photos.shuffled().take((poolSize - videos.size).coerceAtLeast(0)), videos)
    }

    private fun videoSlide(item: VideoItem) =
        Slide("$rootPath\\${item.path.replace('/', '\\')}", "", SlideKind.VIDEO, item)

    /**
     * Walks the share and returns a uniform sample of at most [limit] photos and how many photos were
     * seen. Photos in [exclusion] are skipped before they are counted or sampled.
     */
    private fun walkPhotoSlides(limit: Int, exclusion: PathSet): Pair<List<Slide>, Int> {
        val startedAt = SystemClock.elapsedRealtime()
        var skippedDirectories = 0
        var excludedCount = 0
        var supportedPhotoCount = 0
        val slides = withDiskShare { diskShare ->
            skippedDirectories = 0
            excludedCount = 0
            supportedPhotoCount = 0
            val directories = ArrayDeque<String>()
            val pool = mutableListOf<Slide>()
            directories.add(rootPath)

            while (directories.isNotEmpty()) {
                val directory = directories.removeFirst()
                val entries = try {
                    diskShare.list(directory)
                } catch (error: Exception) {
                    // A folder Guest cannot open must not hide the rest of the library, but a
                    // dropped connection (or an unreadable root) must fail the whole listing.
                    if (directory == rootPath || error.smbFailureKind() != SmbFailureKind.PER_FILE) {
                        throw error
                    }
                    skippedDirectories += 1
                    continue
                }
                for (entry in entries) {
                    val name = entry.fileName
                    if (name == "." || name == ".." || name.startsWith('.')) continue
                    val path = "$directory\\$name"
                    val isDirectory = entry.fileAttributes and
                        FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    if (isDirectory) {
                        directories.addLast(path)
                    } else if (isSupportedPhoto(name)) {
                        if (exclusion.excludesSmbPath(rootPath, path)) {
                            excludedCount += 1
                            continue
                        }
                        supportedPhotoCount += 1
                        val candidate = Slide(path, "Photo $supportedPhotoCount")
                        if (pool.size < limit) {
                            pool += candidate
                        } else {
                            val replacementIndex = Random.nextInt(supportedPhotoCount)
                            if (replacementIndex < limit) pool[replacementIndex] = candidate
                        }
                    }
                }
            }
            pool.shuffle()
            pool
        }
        Log.i(
            LOG_TAG,
            "Photo listing found ${slides.size} pooled photos in " +
                "${SystemClock.elapsedRealtime() - startedAt} ms, $skippedDirectories folders skipped, " +
                "$excludedCount of ${supportedPhotoCount + excludedCount} photos excluded (list of ${exclusion.size})",
        )
        return slides to supportedPhotoCount
    }

    fun loadAlbumIndex(
        cacheFile: java.io.File,
        refresh: Boolean = false,
        progress: (AlbumCatalogProgress) -> Unit = {},
    ): AlbumIndex {
        if (!refresh) {
            progress(AlbumCatalogProgress("Reading the saved catalog from this TV"))
            val readStartedAt = SystemClock.elapsedRealtime()
            PhotosAlbumCatalog.read(cacheFile)?.let { index ->
                Log.i(
                    LOG_TAG,
                    "Album catalog read from this TV: ${index.albums.size} albums, " +
                        "${index.albums.sumOf { it.paths.size }} paths, ${index.videos.size} library videos, ${index.portraitPaths.size} portrait photos, ${cacheFile.length()} bytes in " +
                        "${SystemClock.elapsedRealtime() - readStartedAt} ms",
                )
                return index
            }
        }
        val segments = rootPath.split('\\')
        if (segments.lastOrNull()?.equals("originals", ignoreCase = true) != true || segments.size < 2) {
            throw AlbumIndexException("Album selection requires the Photos Library originals folder as the SMB library path. Update SMB settings and try again.")
        }
        val libraryPath = segments.dropLast(1).joinToString("\\")
        val stage = DatabaseStage(databaseStageDirectory)
        val timings = PhaseTimings()
        try {
            return stage.withExclusiveAccess {
                progress(AlbumCatalogProgress("Connecting to the Photos SMB share"))
                val synced = timings.time("smb_session") { withDiskShare { diskShare ->
                    stage.sync(SmbDatabaseFiles(diskShare, libraryPath), timings) { done, total ->
                        progress(
                            if (total > 0L) AlbumCatalogProgress("Copying the SQLite snapshot read-only", done, total)
                            else AlbumCatalogProgress("Checking the Photos database snapshot"),
                        )
                    }
                } }
                val sourceModified = maxOf(synced.databaseModifiedMillis, synced.walModifiedMillis ?: 0L)
                if (synced.unchanged) {
                    val current = timings.time("catalog_read") { PhotosAlbumCatalog.read(cacheFile) }
                        ?.takeIf { it.sourceModifiedMillis == sourceModified }
                    if (current != null) {
                        progress(AlbumCatalogProgress("The Photos library has not changed since the saved catalog"))
                        Log.i(LOG_TAG, "Album catalog refresh: library unchanged, kept the saved catalog | ${timings.summary()}")
                        return@withExclusiveAccess current
                    }
                }
                timings.time("prepare_query") { stage.prepareForQuery() }
                val built = try {
                    PhotosAlbumCatalog.build(synced.databaseFile, timings) { message -> progress(AlbumCatalogProgress(message)) }
                } catch (error: Throwable) {
                    // The staged copy may be damaged; make the next refresh start from a clean copy.
                    stage.clear()
                    throw error
                }
                val index = built.copy(sourceModifiedMillis = sourceModified)
                progress(AlbumCatalogProgress("Saving the catalog on this TV"))
                timings.time("catalog_write") { PhotosAlbumCatalog.write(cacheFile, index) }
                timings.addBytes("catalog_write", cacheFile.length())
                Log.i(
                    LOG_TAG,
                    "Album catalog refresh: database ${if (synced.reusedDatabase) "reused" else "copied"}, " +
                        "WAL ${if (synced.walResumedFrom > 0L) "resumed at ${synced.walResumedFrom}" else "fetched from start"}, " +
                        "${synced.databaseBytesFetched + synced.walBytesFetched} bytes fetched, " +
                        "${synced.localWalBytes} WAL bytes kept | ${timings.summary()}",
                )
                index
            }
        } catch (error: AlbumIndexException) {
            Log.w(LOG_TAG, "Album catalog refresh failed after: ${timings.summary()}")
            throw error
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Album catalog refresh failed (${error.safeCauseChain()}) after: ${timings.summary()}")
            throw AlbumIndexException("Could not build the album catalog from the Photos database. Check that the library share is available, then try Refresh.")
        }
    }

    /** The Photos database files on the share, read through one open [DiskShare]. */
    private inner class SmbDatabaseFiles(
        private val diskShare: DiskShare,
        private val libraryPath: String,
    ) : RemoteDatabaseFiles {
        private fun pathOf(file: DatabaseFile) = "$libraryPath\\database\\${file.localName}"

        // A directory listing returns size and modification time together and is the same kind of
        // call the photo listing already makes as Guest; per-file attribute queries were denied.
        override fun stat(file: DatabaseFile): RemoteFileInfo? = try {
            diskShare.list("$libraryPath\\database", file.localName)
                .firstOrNull { it.fileName.equals(file.localName, ignoreCase = true) }
                ?.let { RemoteFileInfo(it.endOfFile, it.lastWriteTime.toEpochMillis()) }
        } catch (error: Throwable) {
            val kind = error.smbFailureKind()
            Log.w(LOG_TAG, "Listing of ${file.name} failed as $kind (${error.safeCauseChain()}, ${error.safeSmbStatus()})")
            if (kind == SmbFailureKind.PER_FILE) null else throw error
        }

        override fun openStream(file: DatabaseFile, offset: Long): InputStream =
            if (offset == 0L) openReadOnlyFile(diskShare, pathOf(file))
            else RangedSmbInputStream(openReadOnlyHandle(diskShare, pathOf(file)), offset)
    }

    /** Sequential reads from [startOffset] using one synchronous request per call; for small ranges. */
    private class RangedSmbInputStream(private val file: File, private var position: Long) : InputStream() {
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = file.read(buffer, position, offset, length)
            if (count > 0) position += count
            return count
        }

        override fun close() = file.close()
    }

    private fun loadSelectedAlbumSlides(): List<Slide> {
        val startedAt = SystemClock.elapsedRealtime()
        val slides = loadSelectedAlbumSlidesUntimed()
        Log.i(
            LOG_TAG,
            "Album slides ready: ${slides.count { it.kind == SlideKind.PHOTO }} photos, " +
                "${slides.count { it.kind == SlideKind.VIDEO }} videos in ${SystemClock.elapsedRealtime() - startedAt} ms",
        )
        return slides
    }

    private fun loadSelectedAlbumSlidesUntimed(): List<Slide> {
        if (selectedAlbumIds.isEmpty()) {
            throw AlbumIndexException("No albums are selected. Choose at least one album or switch to All photos.")
        }
        val index = loadAlbumIndex(java.io.File(catalogCacheDirectory, ALBUM_CATALOG_FILE))
        notePortraits(index)
        val byId = index.albums.associateBy { it.id }
        if ((selectedAlbumIds - byId.keys).isNotEmpty()) {
            throw AlbumIndexException("A selected album is no longer in the index. Remove it in Choose albums or return to All photos.")
        }
        val paths = linkedSetOf<String>()
        if (mediaType.includesPhotos) {
            // The catalog builder already leaves excluded photos out of album paths; checking again keeps
            // a hand-edited or older catalog from showing them.
            val exclusion = PathSet(index.excludedPaths)
            var excludedCount = 0
            selectedAlbumIds.forEach { id ->
                byId.getValue(id).paths.forEach { relative ->
                    if (relative in exclusion) {
                        excludedCount += 1
                    } else if (isSafeAlbumPath(relative)) {
                        paths += "$rootPath\\${relative.replace('/', '\\')}"
                    }
                }
            }
            Log.i(LOG_TAG, "Album photos: ${paths.size} kept, $excludedCount excluded (list of ${exclusion.size})")
            checkSampleReadable(paths)
        }
        if (mediaType == MediaType.PHOTOS) {
            if (paths.isEmpty()) throw AlbumIndexException("The selected albums have no readable supported photos. Refresh the Mac index or choose another album.")
            return paths.toList().shuffled().mapIndexed { i, path -> Slide(path, "Photo ${i + 1}") }
        }
        ProgressiveReader.purgeStale(videoCacheDirectory)
        val videos = selectedAlbumIds.flatMap { byId.getValue(it).videos }.distinctBy { it.path }.map(::videoSlide)
        val photos = paths.toList().shuffled().mapIndexed { i, path -> Slide(path, "Photo ${i + 1}") }
        if (photos.isEmpty() && videos.isEmpty()) {
            throw AlbumIndexException(
                if (mediaType == MediaType.VIDEOS) "The selected albums have no videos. Choose other albums or set Type to Photos."
                else "The selected albums have no readable photos or videos. Refresh the Mac index or choose another album.",
            )
        }
        return SlideMixer.shuffleTogether(photos, videos)
    }

    /**
     * Opens a video, waits until the progressive reader says it is safe to start, and reads the label
     * from the container. Throws [VideoSkippedException] when the start wait would be too long.
     */
    override fun prepareVideo(slide: Slide): PreparedVideo {
        val item = slide.video ?: throw IllegalArgumentException("Not a video slide.")
        val remote = SmbRemoteVideo.open(SmbSourceConfig(host, shareName, rootPath), item.path)
        val reader = ProgressiveReader(remote, ProgressiveReader.newCacheFile(videoCacheDirectory), item.durationSeconds)
        try {
            reader.start()
            when (val gate = reader.awaitReadyToPlay()) {
                is GateResult.Ready -> Log.i(
                    LOG_TAG,
                    "Video ready after ${gate.waitedMillis} ms at ${"%.1f".format(gate.bytesPerSecond / 1e6)} MB/s " +
                        "(${"%.0f".format(item.bitrateMbps)} Mbps, ${"%.0f".format(item.durationSeconds)} s)",
                )
                is GateResult.TooSlow -> throw VideoSkippedException("Start would take about ${gate.estimatedWaitMillis / 1000} s", retryLater = true)
                is GateResult.Failed -> throw gate.error
                GateResult.Cancelled -> throw CancellationException("SMB source closed")
            }
            val label = videoLabel(reader)
            Log.i(LOG_TAG, "Video label: ${when { label == null -> "none"; " · " in label -> "place and date"; else -> "one part" }}")
            return PreparedVideo(reader, item.durationSeconds, label)
        } catch (error: Throwable) {
            reader.close()
            throw error
        }
    }

    private fun videoLabel(reader: ProgressiveReader): String? {
        if (labelMode == PhotoLabelMode.NONE) return null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ProgressiveMediaDataSource(reader))
            VideoLabel.format(
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE),
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION),
                labelMode,
                placeNameLookup,
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Checks that at least one of a random sample of [paths] opens. If none does, the share answered
     * but its files are refused or missing (it happened while the clone was refreshing), which is
     * temporary: the failure is logged by SMB status name only and [ShareUnreadableException] makes
     * the player retry and show the stored artworks instead of stopping with an error.
     */
    private fun checkSampleReadable(paths: Collection<String>) {
        val sample = paths.shuffled().take(ReadableSample.SIZE)
        val failures = ReadableSample.check(sample, ::openFailure) ?: return
        Log.w(
            LOG_TAG,
            "None of ${sample.size} sampled photos could be opened: " +
                failures.entries.joinToString { "${it.key} x${it.value}" },
        )
        throw ShareUnreadableException(sample.size)
    }

    /**
     * Null if the file opens for reading, else the SMB status name (or exception class) that refused
     * it. Only a per-file error counts as a refusal; any other error (a dropped connection) is thrown.
     */
    private fun openFailure(smbPath: String): String? = runCatching {
        withDiskShare { share ->
            val file = share.openFile(smbPath, EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            file.close()
        }
        null
    }.getOrElse { error ->
        if (error.smbFailureKind() == SmbFailureKind.PER_FILE) error.safeSmbStatus() ?: error.safeCauseChain() else throw error
    }

    private fun isSafeAlbumPath(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
        return path.isNotBlank() && !path.startsWith('/') && parts.none { it.isBlank() || it == "." || it == ".." } &&
            parts.last().substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
    }

    override fun loadImage(slide: Slide): LoadedPhoto = withDiskShare { diskShare ->
        val open = { openReadOnlyFile(diskShare, slide.id) }
        val metadata = readExifMetadata(
            open = { openMetadataPrefix(open) },
            includeLocation = labelMode == PhotoLabelMode.LOCATION_AND_DATE,
            includeDate = labelMode != PhotoLabelMode.NONE,
            placeNameLookup = placeNameLookup,
        )
        val bitmap = decodeSampledBitmap(
            open = open,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            fallbackCacheDirectory = fallbackCacheDirectory,
            exifOrientation = metadata.orientation,
            useNativeHeif = slide.id.endsWith(".heic", ignoreCase = true) ||
                slide.id.endsWith(".heif", ignoreCase = true),
        )
        LoadedPhoto(bitmap, metadata.label)
    }

    private fun openMetadataPrefix(open: () -> InputStream): InputStream {
        // Android's platform ExifInterface does not reliably read metadata directly from SMBJ's
        // network stream. A bounded local stream preserves EXIF parsing without retaining photos.
        val bytes = ByteArray(MAX_METADATA_BYTES)
        var count = 0
        open().use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read <= 0) break
                count += read
            }
        }
        return ByteArrayInputStream(bytes, 0, count)
    }

    private fun openReadOnlyHandle(diskShare: DiskShare, path: String): File = diskShare.openFile(
        path,
        EnumSet.of(AccessMask.GENERIC_READ),
        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )

    private fun openReadOnlyFile(diskShare: DiskShare, path: String): InputStream {
        val file = openReadOnlyHandle(diskShare, path)
        return object : FilterInputStream(file.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    /**
     * Runs [block] on a shared connection that stays open between photos, so a slideshow does not
     * negotiate, authenticate, and connect a share for every image. A failed call discards the
     * connection so the next one starts clean; a transient failure on a reused (possibly stale)
     * connection is retried once at once on a fresh one.
     */
    private fun <T> withDiskShare(block: (DiskShare) -> T): T {
        val startedEpoch = epoch.get()
        var smb = synchronized(connectionLock) { connection }
        val reused = smb != null
        if (smb == null) {
            smb = openConnection()
            val keep = synchronized(connectionLock) {
                (epoch.get() == startedEpoch).also { if (it) connection = smb }
            }
            if (!keep) {
                closeAsync(smb)
                throw CancellationException("SMB source closed")
            }
        }
        try {
            return block(smb.share)
        } catch (error: Throwable) {
            discard(smb)
            if (epoch.get() != startedEpoch) {
                // close() interrupted this request on purpose (the dream stopped); not a failure.
                Log.d(LOG_TAG, "SMB request cancelled by close")
                throw error
            }
            if (reused && error.smbFailureKind() == SmbFailureKind.TRANSIENT) {
                Log.i(LOG_TAG, "Reused SMB connection failed (${error.safeCauseChain()}); reconnecting")
                return withDiskShare(block)
            }
            Log.w(
                LOG_TAG,
                "SMB request failed on a ${if (reused) "reused" else "new"} connection " +
                    "(${error.safeCauseChain()}, ${error.safeSmbStatus()})",
            )
            throw error
        }
    }

    private fun openConnection(): SmbConnection {
        val config = SmbConfig.builder()
            .withSigningRequired(false)
            .withMultiProtocolNegotiate(true)
            .withTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        var phase = "connect"
        val startedAt = SystemClock.elapsedRealtime()
        val client = SMBClient(config)
        var connection: Connection? = null
        var session: Session? = null
        try {
            connection = client.connect(host, SMB_PORT)
            phase = "authenticate"
            session = connection.authenticate(AuthenticationContext.guest())
            phase = "connectShare"
            val share = session.connectShare(shareName)
            if (share !is DiskShare) {
                share.close()
                throw SmbConfigurationException("The SMB endpoint is not a disk share.")
            }
            return SmbConnection(client, connection, session, share)
        } catch (error: Throwable) {
            Log.w(
                LOG_TAG,
                "SMB $phase failed after ${SystemClock.elapsedRealtime() - startedAt} ms " +
                    "(${error.safeCauseChain()}, ${error.safeSmbStatus()})",
            )
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
            throw error
        }
    }

    private fun discard(smb: SmbConnection) {
        synchronized(connectionLock) { if (connection === smb) connection = null }
        closeAsync(smb)
    }

    /** Closes the shared connection and unblocks any request in flight. Safe from any thread. */
    override fun close() {
        epoch.incrementAndGet()
        val smb = synchronized(connectionLock) { connection.also { connection = null } }
        if (smb != null) closeAsync(smb)
    }

    // Closing sends a logoff and can block on a dead socket, so never do it on the caller's thread.
    private fun closeAsync(smb: SmbConnection) {
        Thread({ smb.closeQuietly() }, "smb-close").apply { isDaemon = true }.start()
    }

    private class SmbConnection(
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
    ) {
        fun closeQuietly() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private fun isSupportedPhoto(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    companion object {
        const val DEFAULT_POOL_SIZE = 250
        const val ALBUM_CATALOG_FILE = "photos-album-catalog-v1.json"
        private const val LOG_TAG = "PhotoSource"
        private const val SMB_PORT = 445
        private const val SMB_TIMEOUT_SECONDS = 15L
        private const val MAX_METADATA_BYTES = 512 * 1024
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    }
}
