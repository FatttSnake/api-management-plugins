package com.example.filebox

import io.swagger.v3.oas.annotations.media.Schema

/**
 * File metadata response
 *
 * The payload of an upload, and the element type of the file list. A declared type rather
 * than a map, because the gateway generates the documentation fragment from these classes.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see Schema
 * @see FileboxFile
 */
data class FileResponse(
    /**
     * Plugin-generated file ID
     */
    @field:Schema(description = "文件 ID", example = "0b0e6d2e-6a3f-4d3a-9a1e-2c0e1f5b7a11")
    val id: String,

    /**
     * Original file name, as uploaded
     */
    @field:Schema(description = "原始文件名", example = "report.pdf")
    val name: String,

    /**
     * Size in bytes
     */
    @field:Schema(description = "字节数", example = "20480")
    val size: Long,

    /**
     * Path of the bytes inside the plugin's own storage namespace
     */
    @field:Schema(description = "插件存储命名空间内的路径", example = "files/1/0b0e6d2e-....pdf")
    val path: String,

    /**
     * Upload time, in epoch milliseconds
     */
    @field:Schema(description = "上传时间，epoch 毫秒", example = "1790000000000")
    val createTime: Long
)

/**
 * Build the API representation of a stored file
 *
 * @return File response
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
fun FileboxFile.toResponse(): FileResponse = FileResponse(
    id = id,
    name = name,
    size = size,
    path = path,
    createTime = createTime
)

/**
 * External link response
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see Schema
 */
data class LinkResponse(
    /**
     * Login-free URL to the file
     */
    @field:Schema(
        description = "免登访问链接",
        example = "https://api.example.com/public/storage/filebox/files/1/xxx.pdf?e=1790003600&s=..."
    )
    val url: String,

    /**
     * Life the link was minted with, in hours
     */
    @field:Schema(description = "外链有效期（小时）", example = "1")
    val expiresInHours: Long,

    /**
     * Notice configured on the plugin, or null when unset
     */
    @field:Schema(description = "插件配置的公告文本，未配置时为 null")
    val notice: String?,

    /**
     * Reminder that a link is a bearer credential
     */
    @field:Schema(description = "外链风险提示")
    val warning: String
)

/**
 * Delete response
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see Schema
 */
data class RemoveResponse(
    /**
     * ID of the deleted file
     */
    @field:Schema(description = "被删除的文件 ID")
    val id: String,

    /**
     * Whether the file was deleted
     */
    @field:Schema(description = "是否删除成功", example = "true")
    val deleted: Boolean
)

/**
 * Snapshot response
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see Schema
 * @see SnapshotResult
 */
data class SnapshotResponse(
    /**
     * SHA-256 of the content, which is also its storage key
     */
    @field:Schema(
        description = "内容 SHA-256，也是存储键",
        example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    )
    val contentKey: String,

    /**
     * Size in bytes
     */
    @field:Schema(description = "字节数", example = "4")
    val size: Long,

    /**
     * Whether the content was already stored, in which case it was shared
     */
    @field:Schema(description = "是否命中已存在的同内容对象", example = "false")
    val deduplicated: Boolean
)

/**
 * Build the API representation of a stored snapshot
 *
 * @return Snapshot response
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
fun SnapshotResult.toResponse(): SnapshotResponse = SnapshotResponse(
    contentKey = contentKey,
    size = size,
    deduplicated = deduplicated
)

/**
 * Snapshot read response
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see Schema
 */
data class ReadSnapshotResponse(
    /**
     * Content key the text was read by
     */
    @field:Schema(description = "内容 SHA-256")
    val contentKey: String,

    /**
     * Text content
     */
    @field:Schema(description = "文本内容")
    val text: String
)
