package com.example.echo

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Echo response (v1)
 *
 * The payload of `/api/echo/v1/ping`. It is a declared type rather than a map so the
 * gateway can describe it: the documentation fragment is generated from these classes on
 * every mount, and a `Map<String, Any?>` would only ever describe itself as an open object.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see Schema
 */
data class PingResponse(
    /**
     * Echo message
     */
    @field:Schema(description = "回声消息", example = "pong")
    val message: String,

    /**
     * Current caller's user ID, or null when unauthenticated
     */
    @field:Schema(description = "当前调用者 ID，未登录时为 null")
    val userId: Long?
)

/**
 * Version response (v1)
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see Schema
 */
data class VersionResponse(
    /**
     * API version the controller declares
     */
    @field:Schema(description = "该控制器声明的 API 版本", example = "1")
    val version: Int,

    /**
     * Operation IDs served under that version
     */
    @field:Schema(description = "该版本下的端点 operationId")
    val apis: List<String>
)

/**
 * Echo response (v2)
 *
 * The payload of `/api/echo/v2/ping`, which carries the version alongside the message.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see Schema
 */
data class PingV2Response(
    /**
     * Echo message
     */
    @field:Schema(description = "回声消息", example = "pong")
    val message: String,

    /**
     * API version the controller declares
     */
    @field:Schema(description = "该控制器声明的 API 版本", example = "2")
    val version: Int,

    /**
     * Current caller's user ID, or null when unauthenticated
     */
    @field:Schema(description = "当前调用者 ID，未登录时为 null")
    val userId: Long?
)

/**
 * Current caller response (v2)
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see Schema
 */
data class WhoamiResponse(
    /**
     * Current caller's user ID, or null when unauthenticated
     */
    @field:Schema(description = "当前调用者 ID，未登录时为 null")
    val userId: Long?,

    /**
     * API key the call came through, or null when the caller is an account
     */
    @field:Schema(description = "当前调用的 API 密钥 ID，账户直连调用时为 null")
    val accessKeyId: Long?
)
