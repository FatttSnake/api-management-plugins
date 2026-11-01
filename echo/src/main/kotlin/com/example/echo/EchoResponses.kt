package com.example.echo

/**
 * Echo response (v1).
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class PingResponse(
    val message: String,
    val userId: Long?
)

/**
 * Version response (v1).
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class VersionResponse(
    val version: Int,
    val apis: List<String>
)

/**
 * Echo response (v2).
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class PingV2Response(
    val message: String,
    val version: Int,
    val userId: Long?
)

/**
 * Current caller response (v2).
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class WhoamiResponse(
    val userId: Long?,
    val accessKeyId: Long?
)
