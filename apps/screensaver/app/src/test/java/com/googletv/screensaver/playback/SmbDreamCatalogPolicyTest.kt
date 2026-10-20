package com.googletv.screensaver.playback

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SmbDreamCatalogPolicyTest {
    @Test
    fun dreamRequiresCatalogWhenMissingInsteadOfOpeningSmbToBuildIt() {
        val directory = createTempDirectory()
        try {
            val source = source(directory, requireSavedCatalog = true)
            try {
                expectRefreshRequired(source)
            } finally {
                source.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dreamRequiresRefreshWhenSavedCatalogIsUnreadableOrOutdated() {
        val directory = createTempDirectory()
        try {
            File(directory, SmbGuestPhotoSource.ALBUM_CATALOG_FILE).writeText("{\"version\":1}")
            val source = source(directory, requireSavedCatalog = true)
            try {
                expectRefreshRequired(source)
            } finally {
                source.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun expectRefreshRequired(source: SmbGuestPhotoSource) {
        try {
            source.loadSlides()
            fail("Expected the DreamService to require an app catalog refresh")
        } catch (error: AlbumIndexException) {
            assertEquals("The Photos catalog needs a refresh. Open the app and refresh the album catalog.", error.message)
        }
    }

    private fun source(directory: File, requireSavedCatalog: Boolean) = SmbGuestPhotoSource(
        config = SmbSourceConfig("photos.local", "Photos", "Photos Library\\originals"),
        targetWidth = 1920,
        targetHeight = 1080,
        fallbackCacheDirectory = directory,
        catalogCacheDirectory = directory,
        requireSavedCatalog = requireSavedCatalog,
    )

    private fun createTempDirectory(): File = Files.createTempDirectory("dream-catalog-policy-").toFile()
}
