package com.example.filebox

import org.springframework.stereotype.Service
import top.fatweb.apimanagement.sdk.plugin.PluginContext
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * Filebox service
 *
 * Everything here goes through the two channels a plugin may use: its own isolated
 * datasources for metadata, and [PluginContext.storage] for bytes. No file is written
 * outside the plugin's storage namespace, which the SDK composes from the plugin ID.
 *
 * File rows live in the MySQL datasource an administrator configures, snapshots in the
 * SQLite one the gateway derives.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see PluginContext
 */
@Service
class FileboxService(private val pluginContext: PluginContext) {
    private companion object {
        /**
         * Datasource holding the files themselves
         */
        const val MAIN = "main"

        /**
         * Datasource holding the text snapshots
         */
        const val CACHE = "cache"

        /**
         * Single-file size limit in MB
         */
        const val MAX_FILE_SIZE_MB = "maxFileSizeMb"

        /**
         * Whether text snapshots are enabled
         */
        const val ALLOW_SNAPSHOT = "allowSnapshot"

        /**
         * Default life of a minted external link, in hours
         */
        const val DEFAULT_LINK_TTL_HOURS = "defaultLinkTtlHours"

        /**
         * Notice the API echoes back, to show a text setting being read
         */
        const val PUBLIC_NOTICE = "publicNotice"

        /**
         * Shared token an upload has to present, when one is configured
         */
        const val UPLOAD_TOKEN = "uploadToken"

        const val MEGABYTE = 1024L * 1024L
        const val MAX_NAME_LENGTH = 255
    }

    /**
     * Upload a file
     *
     * The bytes go to a path the plugin picks, which is what makes them readable later
     * through an external link; the original file name is kept in the database only.
     *
     * @param ownerId Owner ID
     * @param fileName Original file name
     * @param bytes File content
     * @return Stored file metadata
     * @throws FileboxException when the file is empty, too large, or the file datasource
     *         has not been configured yet
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun upload(ownerId: Long, fileName: String, bytes: ByteArray): FileboxFile {
        val repository = repository()

        if (bytes.isEmpty()) {
            throw FileboxException(FileboxCode.INVALID_ARGUMENT, "文件内容为空")
        }
        val limit = intConfig(MAX_FILE_SIZE_MB, 10) * MEGABYTE
        if (bytes.size > limit) {
            throw FileboxException(
                FileboxCode.FILE_TOO_LARGE,
                "文件大小 ${bytes.size} 字节，超过配置项 $MAX_FILE_SIZE_MB 的上限 ${limit / MEGABYTE} MB"
            )
        }

        val id = UUID.randomUUID().toString()
        val path = "files/$ownerId/$id${extensionOf(fileName)}"
        pluginContext.storage.saveFile(path, bytes)

        val file = FileboxFile(
            id = id,
            ownerId = ownerId,
            name = fileName.take(MAX_NAME_LENGTH).ifBlank { "file" },
            size = bytes.size.toLong(),
            path = path,
            createTime = System.currentTimeMillis()
        )
        repository.insertFile(file)

        return file
    }

    /**
     * List the files of a user
     *
     * @param ownerId Owner ID
     * @return File metadata, newest first
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun list(ownerId: Long): List<FileboxFile> = repository().listFiles(ownerId)

    /**
     * Read a file the given user owns
     *
     * @param ownerId Owner ID
     * @param id File ID
     * @return File metadata
     * @throws FileboxException when the file does not exist or belongs to somebody else
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun requireFile(ownerId: Long, id: String): FileboxFile {
        val file = repository().findFile(id)
            ?: throw FileboxException(FileboxCode.FILE_NOT_FOUND, "文件不存在：$id")
        if (file.ownerId != ownerId) {
            throw FileboxException(FileboxCode.ACCESS_DENIED, "无权访问该文件")
        }

        return file
    }

    /**
     * Download a file
     *
     * The bytes come back exactly as stored: location-addressed objects are not compressed,
     * which is what lets the same object be handed to a browser.
     *
     * @param ownerId Owner ID
     * @param id File ID
     * @return File metadata and content
     * @throws FileboxException when the file or its content is gone
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun download(ownerId: Long, id: String): Pair<FileboxFile, ByteArray> {
        val file = requireFile(ownerId, id)
        val bytes = pluginContext.storage.loadFile(file.path)
            ?: throw FileboxException(FileboxCode.FILE_NOT_FOUND, "文件内容已丢失：$id")

        return file to bytes
    }

    /**
     * Mint a login-free link to a file
     *
     * The link is a bearer credential: readable until it expires and not revocable before
     * then. Deleting the file does make it fail immediately.
     *
     * @param ownerId Owner ID
     * @param id File ID
     * @param ttlHours Requested life in hours, or null for the configured default
     * @return External URL and the life it was minted with
     * @throws FileboxException when the file is gone or the gateway cannot build a URL
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun externalUrl(ownerId: Long, id: String, ttlHours: Long?): Pair<String, Long> {
        val file = requireFile(ownerId, id)
        // The gateway answers with the declared default, so the administrator never has to set it
        val hours = ttlHours ?: intConfig(DEFAULT_LINK_TTL_HOURS, 1).toLong()
        if (hours <= 0) {
            throw FileboxException(FileboxCode.INVALID_ARGUMENT, "外链有效期必须大于 0")
        }

        val url = pluginContext.storage.fileExternalUrl(file.path, Duration.ofHours(hours))
            ?: throw FileboxException(
                FileboxCode.EXTERNAL_URL_UNAVAILABLE,
                "网关无法生成外链，请配置 app.storage.public-base-url"
            )

        return url to hours
    }

    /**
     * Delete a file
     *
     * @param ownerId Owner ID
     * @param id File ID
     * @throws FileboxException when the file does not exist or belongs to somebody else
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun delete(ownerId: Long, id: String) {
        val file = requireFile(ownerId, id)

        repository().deleteFile(id)
        pluginContext.storage.deleteFile(file.path)
    }

    /**
     * Store a text snapshot
     *
     * Addressed by its own SHA-256, so storing the same text twice shares one object and
     * one row. Reach for it when the bytes never have to reach a browser: the gateway keeps
     * these compressed, which is why it refuses to mint a link for one.
     *
     * @param ownerId Owner ID
     * @param text Text content
     * @return Stored snapshot information
     * @throws FileboxException when snapshots are disabled
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun snapshot(ownerId: Long, text: String): SnapshotResult {
        // Read per call, so a config change is visible on the very next invocation - no remount
        if (!booleanConfig(ALLOW_SNAPSHOT, true)) {
            throw FileboxException(FileboxCode.SNAPSHOT_DISABLED, "文本快照功能未启用，可在插件配置中开启")
        }
        if (text.isEmpty()) {
            throw FileboxException(FileboxCode.INVALID_ARGUMENT, "文本内容为空")
        }

        val bytes = text.toByteArray(Charsets.UTF_8)
        val contentKey = sha256Hex(bytes)
        val repository = snapshotRepository()
        val alreadyStored = pluginContext.storage.existsContent(contentKey)
        pluginContext.storage.saveContent(bytes)

        if (repository.findSnapshot(contentKey) == null) {
            repository.insertSnapshot(
                FileboxSnapshot(
                    contentKey = contentKey,
                    ownerId = ownerId,
                    size = bytes.size.toLong(),
                    createTime = System.currentTimeMillis()
                )
            )
        }

        return SnapshotResult(contentKey = contentKey, size = bytes.size.toLong(), deduplicated = alreadyStored)
    }

    /**
     * Read a text snapshot
     *
     * @param ownerId Owner ID
     * @param contentKey Content key
     * @return Text content
     * @throws FileboxException when the snapshot does not exist or belongs to somebody else
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun readSnapshot(ownerId: Long, contentKey: String): String {
        val snapshot = snapshotRepository().findSnapshot(contentKey)
            ?: throw FileboxException(FileboxCode.SNAPSHOT_NOT_FOUND, "快照不存在：$contentKey")
        if (snapshot.ownerId != ownerId) {
            throw FileboxException(FileboxCode.ACCESS_DENIED, "无权访问该快照")
        }

        val bytes = pluginContext.storage.loadContent(contentKey)
            ?: throw FileboxException(FileboxCode.SNAPSHOT_NOT_FOUND, "快照内容已丢失：$contentKey")

        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Check the shared upload token, when one is configured
     *
     * The token is a secret setting: the gateway stores it encrypted and never returns it
     * through the API, while the plugin reads it back as plaintext here.
     *
     * @param token Token presented by the caller
     * @throws FileboxException when a token is configured and does not match
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun verifyUploadToken(token: String?) {
        val expected = pluginContext.getSetting(UPLOAD_TOKEN)?.takeIf { it.isNotEmpty() } ?: return

        if (token != expected) {
            throw FileboxException(FileboxCode.UPLOAD_TOKEN_MISMATCH, "上传令牌不匹配")
        }
    }

    /**
     * Read the notice to echo back with a link
     *
     * @return Configured notice, or null when unset
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun publicNotice(): String? = pluginContext.getSetting(PUBLIC_NOTICE)?.takeIf { it.isNotEmpty() }

    /**
     * Open a repository over the datasource holding the files
     */
    private fun repository(): FileboxRepository = FileboxRepository(requireDataSource(MAIN))

    /**
     * Open a repository over the datasource holding the snapshots
     */
    private fun snapshotRepository(): FileboxRepository = FileboxRepository(requireDataSource(CACHE))

    /**
     * Resolve one of the plugin's datasources
     *
     * `main` is absent from `context.datasources` until the administrator fills it in;
     * `cache`, a SQLite one the gateway derives, is always there. Reporting the missing one
     * from the endpoint beats refusing to mount.
     *
     * @param name Datasource name
     * @throws FileboxException when the gateway supplied no such datasource
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    private fun requireDataSource(name: String): DataSource = pluginContext.datasources[name]
        ?: throw FileboxException(
            FileboxCode.DATASOURCE_NOT_CONFIGURED,
            "插件数据源 '$name' 不可用，请在插件配置中填写该数据源的连接信息"
        )

    /**
     * Read an integer setting
     *
     * The fallback only covers a value the gateway would never have stored; an unset key
     * reads as the schema default.
     */
    private fun intConfig(key: String, fallback: Int): Int =
        pluginContext.getSetting(key)?.toIntOrNull() ?: fallback

    /**
     * Read a boolean setting
     */
    private fun booleanConfig(key: String, fallback: Boolean): Boolean =
        pluginContext.getSetting(key)?.toBooleanStrictOrNull() ?: fallback

    /**
     * Build a storage-safe extension from a user-supplied file name
     *
     * Anything that is not a short ASCII word is dropped: a storage path segment must be
     * URL-safe, a file name need not be.
     */
    private fun extensionOf(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
            .filter { it.isDigit() || it in 'a'..'z' || it in 'A'..'Z' }
            .lowercase()

        return if (extension.isEmpty() || extension.length > 10) "" else ".$extension"
    }

    /**
     * Hash content the same way the gateway addresses it
     */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
