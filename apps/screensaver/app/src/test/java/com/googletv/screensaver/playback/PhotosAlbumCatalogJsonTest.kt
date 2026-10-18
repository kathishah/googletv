package com.googletv.screensaver.playback

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhotosAlbumCatalogJsonTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sample = AlbumIndex(
        albums = listOf(
            AlbumEntry(
                "A1", "Trip", "2025-09", listOf("0/a.jpg", "0/b.heic"),
                listOf(VideoItem("0/c.mov", 31.633, 31_000_000L)),
            ),
            AlbumEntry("A2", "Empty", null, emptyList(), emptyList()),
        ),
        createdAtMillis = 1_700_000_000_000L,
        sourceModifiedMillis = 1_700_000_100_000L,
        videos = listOf(VideoItem("0/c.mov", 31.633, 31_000_000L), VideoItem("9/d.mp4", 4.0, 9_000_000L)),
    )

    private fun json(change: (JSONObject) -> Unit): String =
        JSONObject(PhotosAlbumCatalog.encode(sample)).also(change).toString()

    private fun assertRejected(text: String) {
        try {
            PhotosAlbumCatalog.parse(text)
            fail("expected the catalog to be rejected")
        } catch (_: Exception) {
            // expected: any validation failure
        }
    }

    @Test
    fun encodeThenParseRoundTrips() {
        assertEquals(sample, PhotosAlbumCatalog.parse(PhotosAlbumCatalog.encode(sample)))
    }

    @Test
    fun theSourceMarkerIsOptional() {
        val parsed = PhotosAlbumCatalog.parse(json { it.remove("sourceModifiedAt") })
        assertNull(parsed.sourceModifiedMillis)
    }

    @Test
    fun version1CatalogsAreRejectedSoTheyGetRebuilt() {
        assertRejected(json { it.put("version", 1) })
        assertEquals(2, PhotosAlbumCatalog.VERSION)
    }

    @Test
    fun missingVideoSectionsAreRejected() {
        assertRejected(json { it.remove("videos") })
        assertRejected(json { it.getJSONArray("albums").getJSONObject(0).remove("videos") })
    }

    @Test
    fun countsMustMatchTheLists() {
        assertRejected(json { it.getJSONArray("albums").getJSONObject(0).put("photoCount", 5) })
        assertRejected(json { it.getJSONArray("albums").getJSONObject(0).put("videoCount", 0) })
        assertRejected(json { it.getJSONArray("albums").getJSONObject(0).remove("videoCount") })
    }

    @Test
    fun unsafeOrWrongTypePathsAreRejected() {
        assertRejected(json { it.getJSONArray("albums").getJSONObject(0).getJSONArray("paths").put(0, "../x.jpg") })
        assertRejected(json { it.getJSONArray("albums").getJSONObject(0).getJSONArray("paths").put(0, "0/clip.mov") })
        assertRejected(json { it.getJSONArray("videos").getJSONObject(0).put("p", "0/photo.jpg") })
        assertRejected(json { it.getJSONArray("videos").getJSONObject(0).put("p", "/abs/clip.mov") })
    }

    @Test
    fun badVideoNumbersAreRejected() {
        assertRejected(json { it.getJSONArray("videos").getJSONObject(0).put("d", 0.0) })
        assertRejected(json { it.getJSONArray("videos").getJSONObject(0).put("s", 0) })
        assertRejected(json { it.getJSONArray("videos").getJSONObject(0).put("d", -2.0) })
    }

    @Test
    fun duplicatesAreRejected() {
        assertRejected(json { it.getJSONArray("videos").getJSONObject(1).put("p", "0/c.mov") })
        assertRejected(json { it.getJSONArray("albums").getJSONObject(1).put("id", "A1") })
    }

    @Test
    fun wrongPathRootOrCreatedAtIsRejected() {
        assertRejected(json { it.put("pathRoot", "Other/originals") })
        assertRejected(json { it.put("createdAt", 0) })
    }

    @Test
    fun writeThenReadWorksAndBadFilesReadAsNull() {
        val file = File(temporaryFolder.root, "catalog.json")
        assertNull(PhotosAlbumCatalog.read(file))
        PhotosAlbumCatalog.write(file, sample)
        assertEquals(sample, PhotosAlbumCatalog.read(file))
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        file.writeText("not json")
        assertNull(PhotosAlbumCatalog.read(file))
        file.writeText(json { it.put("version", 1) })
        assertNull(PhotosAlbumCatalog.read(file))
    }
}
