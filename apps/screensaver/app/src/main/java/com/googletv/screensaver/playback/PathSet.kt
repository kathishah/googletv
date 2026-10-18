package com.googletv.screensaver.playback

/**
 * A set of paths relative to `originals`, looked up the way the catalog needs. It wraps the catalog's
 * `excluded` list (photos the slideshow never shows: screenshots, documents, receipts) for the random
 * walk in All mode and the album paths, and its `portrait` list (photos that may share a slide).
 *
 * Paths compare case-insensitively with either slash, so a spelling difference between the Photos
 * database and the share's directory listing cannot let an excluded photo through or hide a portrait.
 */
internal class PathSet(paths: Set<String>) {
    private val keys: Set<String> = paths.mapTo(HashSet()) { normalize(it) }

    val size: Int get() = keys.size
    val isEmpty: Boolean get() = keys.isEmpty()

    /** [relativePath] is relative to `originals`, with `/` or `\`. */
    operator fun contains(relativePath: String): Boolean = normalize(relativePath) in keys

    /** True if the SMB path [smbPath] under [rootPath] (both `\`-separated) is in the set. */
    fun excludesSmbPath(rootPath: String, smbPath: String): Boolean = containsSmbPath(rootPath, smbPath)

    fun containsSmbPath(rootPath: String, smbPath: String): Boolean {
        if (keys.isEmpty() || smbPath.length <= rootPath.length + 1) return false
        return normalize(smbPath.substring(rootPath.length + 1)) in keys
    }

    companion object {
        val NONE = PathSet(emptySet())

        private fun normalize(path: String) = path.replace('\\', '/').lowercase()
    }
}
