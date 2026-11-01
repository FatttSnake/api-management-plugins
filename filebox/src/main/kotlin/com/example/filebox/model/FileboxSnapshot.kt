package com.example.filebox.model

/**
 * Snapshot metadata.
 *
 * @property contentKey SHA-256 of the content, which is also its storage key
 * @property ownerId ID of the user who stored the content first
 * @property size size in bytes
 * @property createTime store time, in epoch milliseconds
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class FileboxSnapshot(
    val contentKey: String,
    val ownerId: Long,
    val size: Long,
    val createTime: Long,
)
