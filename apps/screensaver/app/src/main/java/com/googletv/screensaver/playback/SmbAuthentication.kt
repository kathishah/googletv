package com.googletv.screensaver.playback

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.TimeUnit

enum class SmbAuthMode { GUEST, REGISTERED }

/** SMB sign-in values. Its string form intentionally never exposes the password. */
data class SmbAuthentication(
    val mode: SmbAuthMode = SmbAuthMode.GUEST,
    val username: String = "",
    val password: String = "",
) {
    fun validated(): SmbAuthentication {
        if (mode == SmbAuthMode.REGISTERED) {
            require(username.isNotBlank()) { "Enter the registered SMB username." }
            require(password.isNotEmpty()) { "Enter the registered SMB password." }
        }
        return copy(username = username.trim())
    }

    fun toAuthenticationContext(): AuthenticationContext = when (mode) {
        SmbAuthMode.GUEST -> AuthenticationContext.guest()
        SmbAuthMode.REGISTERED -> {
            val valid = validated()
            AuthenticationContext(valid.username, valid.password.toCharArray(), "")
        }
    }

    override fun toString(): String = "SmbAuthentication(mode=$mode, registered=${username.isNotBlank()})"
}

/** A short, read-only probe that verifies SMB auth, share access, and folder enumeration. */
object SmbConnectionTester {
    fun test(config: SmbSourceConfig, authentication: SmbAuthentication) {
        val target = config.validated()
        val auth = authentication.validated()
        val smbConfig = SmbConfig.builder()
            .withSigningRequired(false)
            .withMultiProtocolNegotiate(true)
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(smbConfig)
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        try {
            connection = client.connect(target.host, 445)
            session = connection.authenticate(auth.toAuthenticationContext())
            share = session.connectShare(target.share) as? DiskShare
                ?: throw IllegalStateException("The SMB endpoint is not a disk share.")
            if (!share.folderExists(target.rootPath)) {
                throw IllegalStateException("The library folder was not found in this share.")
            }
            share.list(target.rootPath)
        } finally {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
        }
    }
}
