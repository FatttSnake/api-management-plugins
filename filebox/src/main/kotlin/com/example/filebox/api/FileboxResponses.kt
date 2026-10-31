package com.example.filebox.api

import com.example.filebox.model.FileboxFile
import com.example.filebox.model.FileboxPage
import com.example.filebox.model.SnapshotResult

/**
 * File metadata response.
 *
 * @property id plugin-generated file ID
 * @property name original file name
 * @property size size in bytes
 * @property path path inside the plugin storage namespace
 * @property createTime upload time in epoch milliseconds
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class FileResponse(
    val id: String,
    val name: String,
    val size: Long,
    val path: String,
    val createTime: Long
)

/**
 * Builds the API representation of a stored file.
 *
 * @return file response
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
 * One page of files.
 *
 * @property records records of this page
 * @property total number of files matching the query
 * @property page current page number, starting at 1
 * @property size page size
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class FilePageResponse(
    val records: List<FileResponse>,
    val total: Long,
    val page: Long,
    val size: Long
)

/**
 * Builds the API representation of one page of files.
 *
 * @return page response
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
fun FileboxPage<FileboxFile>.toResponse(): FilePageResponse = FilePageResponse(
    records = records.map(FileboxFile::toResponse),
    total = total,
    page = page,
    size = size
)

/**
 * External link response.
 *
 * @property url login-free URL to the file
 * @property expiresInHours link lifetime in hours
 * @property notice configured notice, or null when unset
 * @property warning reminder that a link is a bearer credential
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class LinkResponse(
    val url: String,
    val expiresInHours: Long,
    val notice: String?,
    val warning: String
)

/**
 * Delete response.
 *
 * @property id ID of the deleted file
 * @property deleted whether the file was deleted
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class RemoveResponse(
    val id: String,
    val deleted: Boolean
)

/**
 * Snapshot response.
 *
 * @property contentKey SHA-256 of the content, which is also its storage key
 * @property size size in bytes
 * @property deduplicated whether the content was already stored
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class SnapshotResponse(
    val contentKey: String,
    val size: Long,
    val deduplicated: Boolean
)

/**
 * Builds the API representation of a stored snapshot.
 *
 * @return snapshot response
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
fun SnapshotResult.toResponse(): SnapshotResponse = SnapshotResponse(
    contentKey = contentKey,
    size = size,
    deduplicated = deduplicated
)

/**
 * Snapshot read response.
 *
 * @property contentKey content key the text was read by
 * @property text text content
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class ReadSnapshotResponse(
    val contentKey: String,
    val text: String
)
