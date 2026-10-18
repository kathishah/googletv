package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class AlbumCatalogMapperTest {
    private val mapper = AlbumCatalogMapper(ZoneOffset.UTC)

    private fun row(
        album: String? = "A1",
        title: String? = "Trip",
        kind: Int? = 0,
        directory: String? = "0",
        filename: String? = "a.jpg",
        start: Double? = null,
        end: Double? = null,
    ) = AlbumRow(album, title, start, end, kind, directory, filename)

    private fun videoRow(name: String, kind: Int? = 1, directory: String? = "0") = row(kind = kind, directory = directory, filename = name)

    private fun library(name: String, duration: Double? = 30.0, size: Long? = 30_000_000L, directory: String? = "0") =
        mapper.addLibraryVideo(VideoRow(directory, name, duration, size))

    private fun built() = mapper.build(1234L)

    @Test
    fun photosKeepTheVersion1Rules() {
        mapper.addAlbumRow(row(filename = "a.jpg"))
        mapper.addAlbumRow(row(directory = "0\\1", filename = "b.HEIC", kind = 1)) // kind 1 with a photo extension stays a photo
        mapper.addAlbumRow(row(filename = "c.dng"))
        mapper.addAlbumRow(row(filename = "d.mov", kind = 0)) // a video-looking file on a photo asset
        val album = built().albums.single()
        assertEquals(listOf("0/a.jpg", "0/1/b.HEIC"), album.paths)
        assertEquals(2, album.photoCount)
        assertEquals(0, album.videoCount)
    }

    @Test
    fun anAlbumVideoMustBeKindOneAndInTheLibraryList() {
        library("ok.mov")
        mapper.addAlbumRow(videoRow("ok.mov"))
        mapper.addAlbumRow(videoRow("ghost.mov")) // trashed, hidden, no size, or stored outside originals: not in the library list
        mapper.addAlbumRow(videoRow("ok.mov", kind = 0)) // a photo-kind asset is never a video
        mapper.addAlbumRow(videoRow("ok.mov", kind = null))
        val album = built().albums.single()
        assertEquals(listOf("0/ok.mov"), album.videos.map { it.path })
        assertEquals(1, album.videoCount)
    }

    @Test
    fun albumVideosCarryTheLibraryDurationAndSize() {
        library("a.mov", duration = 12.3456789, size = 5_000_000L)
        mapper.addAlbumRow(videoRow("a.mov"))
        val video = built().albums.single().videos.single()
        assertEquals(12.346, video.durationSeconds, 0.0)
        assertEquals(5_000_000L, video.sizeBytes)
    }

    @Test
    fun theOrderOfLibraryAndAlbumCallsDoesNotMatter() {
        mapper.addAlbumRow(videoRow("late.mov"))
        library("late.mov")
        assertEquals(listOf("0/late.mov"), built().albums.single().videos.map { it.path })
    }

    @Test
    fun duplicateMembershipsAreCollapsedAndOrderIsKept() {
        library("b.mov")
        library("a.mov")
        mapper.addAlbumRow(videoRow("b.mov"))
        mapper.addAlbumRow(videoRow("a.mov"))
        mapper.addAlbumRow(videoRow("b.mov"))
        assertEquals(listOf("0/b.mov", "0/a.mov"), built().albums.single().videos.map { it.path })
    }

    @Test
    fun unsafeVideoPathsNeverResolve() {
        library("x.mov", directory = "../0")
        mapper.addAlbumRow(videoRow("x.mov", directory = "../0"))
        mapper.addAlbumRow(videoRow("x.mov", directory = null))
        assertTrue(built().albums.single().videos.isEmpty())
        assertTrue(built().videos.isEmpty())
    }

    @Test
    fun everySupportedVideoExtensionIsAccepted() {
        CatalogPaths.videoExtensions.forEach {
            library("clip.$it", directory = "e/$it")
            mapper.addAlbumRow(videoRow("clip.$it", directory = "e/$it"))
        }
        assertEquals(CatalogPaths.videoExtensions.size, built().albums.single().videoCount)
    }

    @Test
    fun libraryVideosAreValidatedAndDeduplicated() {
        library("a.mov")
        library("a.mov")
        library("b.jpg")
        library("c.mov", duration = null)
        library("c0.mov", duration = 0.0)
        library("cn.mov", duration = Double.NaN)
        library("d.mov", directory = null)
        library("e.mov", size = 0L)
        library("f.mov", size = null)
        library("g.wmv")
        val index = built()
        assertEquals(listOf("0/a.mov"), index.videos.map { it.path })
        assertEquals(1234L, index.createdAtMillis)
        assertNull(index.sourceModifiedMillis)
    }

    @Test
    fun unsafePhotoPathsAreRejected() {
        mapper.addAlbumRow(row(directory = "..", filename = "x.jpg"))
        mapper.addAlbumRow(row(directory = "/abs", filename = "x.jpg"))
        mapper.addAlbumRow(row(directory = "0", filename = ""))
        assertTrue(built().albums.single().paths.isEmpty())
    }

    @Test
    fun albumsWithoutAssetsAreKeptWithZeroCounts() {
        mapper.addAlbumRow(row(album = "Empty", title = "Nothing here", kind = null, directory = null, filename = null))
        val album = built().albums.single()
        assertEquals("Nothing here", album.title)
        assertEquals(0, album.photoCount)
        assertEquals(0, album.videoCount)
    }

    @Test
    fun rowsWithoutAnAlbumIdAreIgnoredAndBlankTitlesGetADefault() {
        mapper.addAlbumRow(row(album = null))
        mapper.addAlbumRow(row(album = "B", title = "  "))
        assertEquals(listOf("Untitled album"), built().albums.map { it.title })
    }

    @Test
    fun theFirstRowDecidesTheAlbumTitleAndDate() {
        mapper.addAlbumRow(row(title = "First", start = 779_587_200.0, end = null))
        mapper.addAlbumRow(row(title = "Second", filename = "b.jpg", start = 1.0, end = 2.0))
        val album = built().albums.single()
        assertEquals("First", album.title)
        assertEquals("2025-09", album.yearMonth) // 2025-09-15 00:00 UTC
        assertEquals(2, album.photoCount)
    }

    @Test
    fun excludedPhotosAreDroppedFromEveryAlbumButKeptInTheList() {
        mapper.addAlbumRow(row(album = "A1", filename = "keep.jpg"))
        mapper.addAlbumRow(row(album = "A1", filename = "scan.heic"))
        mapper.addAlbumRow(row(album = "A2", title = "Other", filename = "SCAN.HEIC")) // case differs from the list
        mapper.addExcludedPhoto("0", "scan.heic")
        mapper.addExcludedPhoto("0", "scan.heic") // twice: still one entry
        mapper.addExcludedPhoto("0", "not-in-any-album.png")
        val index = built()
        assertEquals(listOf("0/keep.jpg"), index.albums[0].paths)
        assertEquals(1, index.albums[0].photoCount)
        assertTrue(index.albums[1].paths.isEmpty())
        assertEquals(setOf("0/scan.heic", "0/not-in-any-album.png"), index.excludedPaths)
    }

    @Test
    fun unsafeOrUnsupportedExcludedPathsAreIgnored() {
        mapper.addExcludedPhoto("..", "x.jpg")
        mapper.addExcludedPhoto(null, "x.jpg")
        mapper.addExcludedPhoto("0", null)
        mapper.addExcludedPhoto("0", "clip.mov")
        assertTrue(built().excludedPaths.isEmpty())
    }

    private fun portrait(name: String, width: Int?, height: Int?, orientation: Int? = 1, directory: String? = "0") =
        mapper.addPortraitPhoto(PortraitRow(directory, name, width, height, orientation))

    @Test
    fun portraitPhotosAreListedByTheirDisplayedShape() {
        portrait("upright.heic", 3024, 4032) // 3:4 stored upright
        portrait("turned.heic", 4032, 3024, orientation = 6) // 4:3 stored, shown turned: portrait
        portrait("turned8.jpg", 4032, 3024, orientation = 8)
        portrait("landscape.heic", 4032, 3024)
        portrait("landscape-turned.heic", 3024, 4032, orientation = 6) // stored portrait, shown landscape
        portrait("tall.jpg", 1000, 2000) // 0.5: taller than 9:16
        portrait("square.jpg", 3000, 3000)
        assertEquals(setOf("0/upright.heic", "0/turned.heic", "0/turned8.jpg"), built().portraitPaths)
    }

    @Test
    fun portraitRowsNeedASizeASafePathAndAPhotoExtension() {
        portrait("a.heic", null, 4032)
        portrait("b.heic", 3024, null)
        portrait("c.heic", 0, 4032)
        portrait("d.heic", 3024, 4032, directory = "..")
        portrait("e.heic", 3024, 4032, directory = null)
        portrait("f.mov", 3024, 4032)
        portrait("g.heic", 3024, 4032, orientation = null) // no orientation recorded: as stored
        assertEquals(setOf("0/g.heic"), built().portraitPaths)
    }

    @Test
    fun excludedPhotosAreNeverListedAsPortrait() {
        portrait("scan.heic", 3024, 4032)
        portrait("SHOT.PNG", 3024, 4032)
        portrait("keep.heic", 3024, 4032)
        mapper.addExcludedPhoto("0", "scan.heic")
        mapper.addExcludedPhoto("0", "shot.png") // case differs
        assertEquals(setOf("0/keep.heic"), built().portraitPaths)
    }

    @Test
    fun aPortraitPhotoNeedsNoAlbumAndDuplicatesCollapse() {
        portrait("solo.heic", 3024, 4032)
        portrait("solo.heic", 3024, 4032)
        assertEquals(setOf("0/solo.heic"), built().portraitPaths)
        assertTrue(built().albums.isEmpty())
    }

    @Test
    fun bitrateComesFromSizeAndDuration() {
        val item = VideoItem("a/b.mov", 10.0, 12_500_000L) // 10 Mbit per second
        assertEquals(10.0, item.bitrateMbps, 1e-9)
    }
}
