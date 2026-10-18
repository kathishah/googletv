package com.googletv.screensaver.playback

/**
 * Photos the slideshow never shows: screenshots, documents, and receipts, as Photos itself
 * classified them. The catalog stores their paths relative to `originals`; this wraps that list for
 * the two places that filter, the random walk in All mode and the album paths.
 *
 * Paths compare case-insensitively with either slash, so a spelling difference between the Photos
 * database and the share's directory listing cannot let an excluded photo through.
 */
internal class PhotoExclusion(paths: Set<String>) {
    private val keys: Set<String> = paths.mapTo(HashSet()) { normalize(it) }

    val size: Int get() = keys.size
    val isEmpty: Boolean get() = keys.isEmpty()

    /** [relativePath] is relative to `originals`, with `/` or `\`. */
    operator fun contains(relativePath: String): Boolean = normalize(relativePath) in keys

    /** True if the SMB path [smbPath] under [rootPath] (both `\`-separated) is excluded. */
    fun excludesSmbPath(rootPath: String, smbPath: String): Boolean {
        if (keys.isEmpty() || smbPath.length <= rootPath.length + 1) return false
        return normalize(smbPath.substring(rootPath.length + 1)) in keys
    }

    companion object {
        val NONE = PhotoExclusion(emptySet())

        private fun normalize(path: String) = path.replace('\\', '/').lowercase()
    }
}
