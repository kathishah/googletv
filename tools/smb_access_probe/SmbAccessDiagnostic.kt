package com.googletv.screensaver.playback

// Diagnostic only; not part of the app. Copy temporarily into
// apps/screensaver/app/src/main/java/com/googletv/screensaver/playback/, build assembleDebug, push the APK
// to /data/local/tmp, and run it with app_process (see docs/DEVICE_TESTING.md). Remove it afterward.
// Read-only opens only. Reads host then
// "ext\tmacResult\trelativePath" lines from stdin. Prints aggregated counts only.

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.SecurityInformation
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.util.EnumSet
import java.util.TreeMap
import java.util.concurrent.TimeUnit

fun main() {
    val lines = generateSequence(::readLine).toList()
    val host = lines.first().trim()
    val samples = lines.drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
    val counts = TreeMap<String, Int>()
    fun count(key: String) { counts[key] = (counts[key] ?: 0) + 1 }

    val config = SmbConfig.builder()
        .withSigningRequired(false)
        .withMultiProtocolNegotiate(true)
        .withTimeout(15, TimeUnit.SECONDS)
        .withSoTimeout(15, TimeUnit.SECONDS)
        .build()
    SMBClient(config).use { client ->
        client.connect(host, 445).use { connection ->
            val session = connection.authenticate(AuthenticationContext.guest())
            println("session guest=${session.sessionContext.isGuest} anonymous=${session.sessionContext.isAnonymous}")
            val share = session.connectShare("Photos") as DiskShare
            for ((ext, macResult, rel) in samples) {
                val group = "$ext/mac=$macResult"
                val path = rel.replace('/', '\\')
                val parent = path.substringBeforeLast('\\')
                val name = path.substringAfterLast('\\')
                count("$group | 0 total")
                count("$group | 1 listing " + runCatching {
                    val e = share.list(parent, name).singleOrNull { it.fileName == name }
                    if (e == null) "missing" else "found size>0=${e.endOfFile > 0}"
                }.fold({ it }, ::status))
                count("$group | 2 open FILE_READ_ATTRIBUTES+stdinfo " + attempt(share, path, EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES)) {
                    "ok size>0=${it.getFileInformation(FileStandardInformation::class.java).endOfFile > 0}"
                })
                count("$group | 3 open READ_CONTROL+dacl " + attempt(share, path, EnumSet.of(AccessMask.READ_CONTROL)) { daclSummary(it) })
                for (mask in listOf(
                    "FILE_READ_DATA" to EnumSet.of(AccessMask.FILE_READ_DATA),
                    "GENERIC_READ(prod)" to EnumSet.of(AccessMask.GENERIC_READ),
                    "MAXIMUM_ALLOWED" to EnumSet.of(AccessMask.MAXIMUM_ALLOWED),
                )) {
                    count("$group | 4 open ${mask.first}+read16 " + attempt(share, path, mask.second) { entry ->
                        val buf = ByteArray(16)
                        "ok bytes=${(entry as File).read(buf, 0)}"
                    })
                }
            }
            share.close()
            session.close()
        }
    }
    counts.forEach { (k, v) -> println("$k = $v") }
}

private fun attempt(share: DiskShare, path: String, mask: Set<AccessMask>, use: (DiskEntry) -> String): String =
    runCatching {
        share.open(
            path, mask, EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use(use)
    }.fold({ it }, ::status)

private fun status(t: Throwable): String =
    if (t is SMBApiException) "err ${t.status}" else "err ${t.javaClass.simpleName}"

private fun daclSummary(entry: DiskEntry): String {
    val sd = entry.getSecurityInformation(
        EnumSet.of(
            SecurityInformation.OWNER_SECURITY_INFORMATION,
            SecurityInformation.GROUP_SECURITY_INFORMATION,
            SecurityInformation.DACL_SECURITY_INFORMATION,
        ),
    )
    val aces = sd.dacl?.aces ?: return "ok dacl=null"
    return "ok dacl=" + aces.joinToString(",") { ace ->
        val sid = ace.sid.toString()
        val who = when {
            sid == sd.ownerSid?.toString() -> "owner"
            sid == sd.groupSid?.toString() -> "group"
            sid == "S-1-1-0" -> "everyone"
            sid == "S-1-5-7" -> "anonymous"
            sid == "S-1-5-32-546" -> "guests"
            else -> "othersid"
        }
        val m = ace.accessMask
        val read = m and 0x1L != 0L || m and 0x80000000L != 0L || m and 0x10000000L != 0L
        "${ace.aceHeader.aceType.name.removeSuffix("_ACE_TYPE")}:$who:read=$read"
    }
}
