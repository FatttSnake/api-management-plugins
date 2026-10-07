package com.example.filebox

/**
 * Filebox snapshot result
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class SnapshotResult(
    /**
     * SHA-256 of the content, which is also its storage key
     */
    val contentKey: String,

    /**
     * Size in bytes
     */
    val size: Long,

    /**
     * Whether the gateway already held this content, in which case storing it again
     * shared the existing object instead of writing a second copy
     */
    val deduplicated: Boolean
)
