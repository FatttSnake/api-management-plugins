package com.example.filebox.model

/**
 * Result of storing a text snapshot.
 *
 * @property contentKey SHA-256 of the content, which is also its storage key
 * @property size size in bytes
 * @property deduplicated whether the storage already held this content
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class SnapshotResult(
    val contentKey: String,
    val size: Long,
    val deduplicated: Boolean,
)
