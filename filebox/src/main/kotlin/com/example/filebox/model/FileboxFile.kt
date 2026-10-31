package com.example.filebox.model

/**
 * File metadata.
 *
 * @property id plugin-generated file ID
 * @property ownerId ID of the user the file belongs to
 * @property name original file name, as uploaded
 * @property size size in bytes
 * @property path path of the bytes inside the plugin's own storage namespace
 * @property createTime upload time, in epoch milliseconds
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class FileboxFile(
    val id: String,
    val ownerId: Long,
    val name: String,
    val size: Long,
    val path: String,
    val createTime: Long,
)
