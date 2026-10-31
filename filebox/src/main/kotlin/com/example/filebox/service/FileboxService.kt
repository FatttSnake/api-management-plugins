package com.example.filebox.service

import com.example.filebox.api.FileboxCode
import com.example.filebox.config.FileboxSettings
import com.example.filebox.db.repository.FileboxFileRepository
import com.example.filebox.db.repository.FileboxSnapshotRepository
import com.example.filebox.model.FileboxFile
import com.example.filebox.model.FileboxPage
import com.example.filebox.model.FileboxSnapshot
import com.example.filebox.model.SnapshotResult
import top.fatweb.apimanagement.api.v1.ApiException
import top.fatweb.apimanagement.api.v1.Storage
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Filebox business logic.
 *
 * Metadata goes through the plugin's migrated datasources and bytes through [Storage], whose
 * namespace the gateway composes from the plugin ID. File rows live in the administrator-
 * configured MySQL datasource, snapshots in the SQLite one the gateway derives.
 *
 * @param settings plugin settings
 * @param storage plugin storage capability
 * @param files file rows, or null while the files datasource is unconfigured
 * @param snapshots snapshot rows, or null when the gateway supplied no snapshot datasource
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class FileboxService(
    private val settings: FileboxSettings,
    private val storage: Storage,
    private val files: FileboxFileRepository?,
    private val snapshots: FileboxSnapshotRepository?,
) {
    /**
     * Uploads a file.
     *
     * The bytes go to a path the plugin picks, which is what makes them readable later through
     * an external link; the original file name is kept in the database only.
     *
     * @param ownerId owner ID
     * @param fileName original file name
     * @param bytes file content
     * @return stored file metadata
     * @throws ApiException when the file is empty, too large, or the file datasource has not
     *         been configured yet
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun upload(ownerId: Long, fileName: String, bytes: ByteArray): FileboxFile {
        val repository = requireFiles()

        if (bytes.isEmpty()) {
            throw ApiException(FileboxCode.INVALID_ARGUMENT, "文件内容为空")
        }
        val limit = settings.maxFileSizeBytes()
        if (bytes.size > limit) {
            throw ApiException(
                FileboxCode.FILE_TOO_LARGE,
                "文件大小 ${bytes.size} 字节，超过配置上限 ${limit / MEGABYTE} MB"
            )
        }

        val id = UUID.randomUUID().toString()
        val path = "files/$ownerId/$id${extensionOf(fileName)}"
        storage.saveFile(path, bytes)

        val file = FileboxFile(
            id = id,
            ownerId = ownerId,
            name = fileName.take(MAX_NAME_LENGTH).ifBlank { "file" },
            size = bytes.size.toLong(),
            path = path,
            createTime = System.currentTimeMillis()
        )
        repository.insert(file)

        return file
    }

    /**
     * Lists the files of a user.
     *
     * @param ownerId owner ID
     * @param name file name prefix, or null for every name
     * @param page page number, starting at 1
     * @param size page size
     * @return one page of file metadata, newest first
     * @throws ApiException when the file datasource has not been configured yet
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun page(ownerId: Long, name: String?, page: Long, size: Long): FileboxPage<FileboxFile> =
        requireFiles().page(ownerId, name, page, size)

    /**
     * Downloads a file the given user owns.
     *
     * The bytes come back exactly as stored: location-addressed objects are not compressed,
     * which is what lets the same object be handed to a browser.
     *
     * @param ownerId owner ID
     * @param id file ID
     * @return file metadata and content
     * @throws ApiException when the file or its content is gone
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun download(ownerId: Long, id: String): Pair<FileboxFile, ByteArray> {
        val file = requireFile(ownerId, id)
        val bytes = storage.loadFile(file.path)
            ?: throw ApiException(FileboxCode.FILE_NOT_FOUND, "文件内容已丢失：$id")

        return file to bytes
    }

    /**
     * Mints a login-free link to a file.
     *
     * The link is a bearer credential: readable until it expires and not revocable before then.
     * Deleting the file does make it fail immediately.
     *
     * @param ownerId owner ID
     * @param id file ID
     * @param ttlHours requested life in hours, or null for the configured default
     * @return external URL and the life it was minted with
     * @throws ApiException when the file is gone or the gateway cannot build a URL
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun externalUrl(ownerId: Long, id: String, ttlHours: Long?): Pair<String, Long> {
        val file = requireFile(ownerId, id)
        // The gateway answers with the declared default, so the administrator never has to set it
        val hours = ttlHours ?: settings.defaultLinkTtlHours()
        if (hours <= 0) {
            throw ApiException(FileboxCode.INVALID_ARGUMENT, "外链有效期必须大于 0")
        }

        val url = storage.fileExternalUrl(file.path, Duration.ofHours(hours))
            ?: throw ApiException(
                FileboxCode.EXTERNAL_URL_UNAVAILABLE,
                "网关无法生成外链，请配置 app.storage.public-base-url"
            )

        return url to hours
    }

    /**
     * Deletes a file.
     *
     * @param ownerId owner ID
     * @param id file ID
     * @throws ApiException when the file does not exist or belongs to somebody else
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun delete(ownerId: Long, id: String) {
        val file = requireFile(ownerId, id)

        requireFiles().delete(id)
        storage.deleteFile(file.path)
    }

    /**
     * Stores a text snapshot.
     *
     * Addressed by its own SHA-256, so storing the same text twice shares one object and one
     * row. Reach for it when the bytes never have to reach a browser: the gateway keeps these
     * compressed, which is why it refuses to mint a link for one.
     *
     * @param ownerId owner ID
     * @param text text content
     * @return stored snapshot information
     * @throws ApiException when snapshots are disabled or no snapshot datasource exists
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun snapshot(ownerId: Long, text: String): SnapshotResult {
        // Read per call, so a config change is visible on the very next invocation - no remount
        if (!settings.allowSnapshot()) {
            throw ApiException(FileboxCode.SNAPSHOT_DISABLED, "文本快照功能未启用，可在插件配置中开启")
        }
        if (text.isEmpty()) {
            throw ApiException(FileboxCode.INVALID_ARGUMENT, "文本内容为空")
        }

        val repository = requireSnapshots()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val contentKey = sha256Hex(bytes)
        val alreadyStored = storage.existsContent(contentKey)
        storage.saveContent(bytes)

        if (repository.find(contentKey) == null) {
            repository.insert(
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
     * Reads a text snapshot.
     *
     * @param ownerId owner ID
     * @param contentKey content key
     * @return text content
     * @throws ApiException when the snapshot does not exist or belongs to somebody else
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun readSnapshot(ownerId: Long, contentKey: String): String {
        val snapshot = requireSnapshots().find(contentKey)
            ?: throw ApiException(FileboxCode.SNAPSHOT_NOT_FOUND, "快照不存在：$contentKey")
        if (snapshot.ownerId != ownerId) {
            throw ApiException(FileboxCode.ACCESS_DENIED, "无权访问该快照")
        }

        val bytes = storage.loadContent(contentKey)
            ?: throw ApiException(FileboxCode.SNAPSHOT_NOT_FOUND, "快照内容已丢失：$contentKey")

        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Reads a file the given user owns.
     */
    private fun requireFile(ownerId: Long, id: String): FileboxFile {
        val file = requireFiles().find(id)
            ?: throw ApiException(FileboxCode.FILE_NOT_FOUND, "文件不存在：$id")
        if (file.ownerId != ownerId) {
            throw ApiException(FileboxCode.ACCESS_DENIED, "无权访问该文件")
        }

        return file
    }

    /**
     * Resolves the files datasource
     *
     * `main` is absent from `context.datasources` until the administrator fills it in. The
     * endpoint reports the missing datasource instead of refusing to mount.
     *
     * @throws ApiException when the gateway supplied no such datasource
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    private fun requireFiles(): FileboxFileRepository = files
        ?: throw ApiException(
            FileboxCode.DATASOURCE_NOT_CONFIGURED,
            "插件数据源 'main' 不可用，请在插件配置中填写该数据源的连接信息"
        )

    /**
     * Resolves the snapshot datasource
     *
     * @throws ApiException when the gateway supplied no such datasource
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    private fun requireSnapshots(): FileboxSnapshotRepository = snapshots
        ?: throw ApiException(
            FileboxCode.DATASOURCE_NOT_CONFIGURED,
            "插件数据源 'cache' 不可用，请在插件配置中填写该数据源的连接信息"
        )

    /**
     * Builds a storage-safe extension from a user-supplied file name
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

    private companion object {
        const val MEGABYTE = 1024L * 1024L
        const val MAX_NAME_LENGTH = 255
    }
}
