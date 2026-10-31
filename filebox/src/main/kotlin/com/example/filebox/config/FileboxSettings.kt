package com.example.filebox.config

import com.example.filebox.api.FileboxCode
import top.fatweb.apimanagement.api.v1.ApiException
import top.fatweb.apimanagement.api.v1.Settings

/**
 * Typed access to the settings the plugin declares in `META-INF/plugin-config.json`.
 *
 * Values are read per call, so an ordinary config change is visible on the next request without
 * a remount. The fallbacks only cover a value the gateway would never have stored.
 *
 * @param settings plugin settings capability
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class FileboxSettings(private val settings: Settings) {
    /**
     * Records the mount time.
     *
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun started() = mark(STARTED_AT)

    /**
     * Records the unmount time.
     *
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun stopped() = mark(STOPPED_AT)

    /**
     * Records the uninstall time.
     *
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun uninstalled() = mark(UNINSTALLED_AT)

    /**
     * Single-file size limit in bytes.
     *
     * @return limit in bytes
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun maxFileSizeBytes(): Long = number(MAX_FILE_SIZE_MB, 10) * MEGABYTE

    /**
     * Whether text snapshots are enabled.
     *
     * @return true when enabled
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun allowSnapshot(): Boolean = settings.get(ALLOW_SNAPSHOT)?.toBooleanStrictOrNull() ?: true

    /**
     * Default life of a minted external link, in hours.
     *
     * @return hours
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun defaultLinkTtlHours(): Long = number(DEFAULT_LINK_TTL_HOURS, 1)

    /**
     * Configured notice echoed back with a link.
     *
     * @return notice, or null when unset
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun publicNotice(): String? = settings.get(PUBLIC_NOTICE)?.takeIf { it.isNotEmpty() }

    /**
     * Checks the shared upload token, when one is configured.
     *
     * The token is a secret setting: the gateway stores it encrypted and never returns it
     * through the admin API, while the plugin reads it back as plaintext here.
     *
     * @param token token presented by the caller
     * @throws ApiException when a token is configured and does not match
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun verifyUploadToken(token: String?) {
        val expected = settings.getSecret(UPLOAD_TOKEN)?.takeIf { it.isNotEmpty() } ?: return
        if (token != expected) {
            throw ApiException(FileboxCode.UPLOAD_TOKEN_MISMATCH, "上传令牌不匹配")
        }
    }

    private fun number(key: String, fallback: Long): Long = settings.get(key)?.toLongOrNull() ?: fallback

    private fun mark(key: String) = settings.save(key, System.currentTimeMillis().toString())

    private companion object {
        const val MAX_FILE_SIZE_MB = "maxFileSizeMb"
        const val ALLOW_SNAPSHOT = "allowSnapshot"
        const val DEFAULT_LINK_TTL_HOURS = "defaultLinkTtlHours"
        const val PUBLIC_NOTICE = "publicNotice"
        const val UPLOAD_TOKEN = "uploadToken"

        const val STARTED_AT = "startedAt"
        const val STOPPED_AT = "stoppedAt"
        const val UNINSTALLED_AT = "uninstalledAt"

        const val MEGABYTE = 1024L * 1024L
    }
}
