package com.example.echo

import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import top.fatweb.apimanagement.sdk.annotation.ApiController
import top.fatweb.apimanagement.sdk.plugin.ApiResponse
import top.fatweb.apimanagement.sdk.plugin.PluginContext

/**
 * Echo controller (v2)
 *
 * Coexists with [EchoController] inside the same plugin: `v2` overrides `v1` for
 * requests at version >= 2 (rolling forward compatibility), demonstrating multiple
 * API versions in one plugin.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see ApiController
 * @see PluginContext
 */
@ApiController(
    plugin = "echo",
    version = 2
)
class EchoControllerV2(
    private val pluginContext: PluginContext
) {
    /**
     * Echo (v2)
     *
     * @return Response object includes the echo message, the version and the caller
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ApiResponse
     */
    @Operation(summary = "回声 v2", description = "返回 v2 回声消息、版本信息与当前调用者", operationId = "ping")
    @GetMapping("/ping")
    fun ping(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            mapOf(
                "message" to "pong",
                "version" to 2,
                "userId" to pluginContext.currentUserId()
            )
        )

    /**
     * Current caller
     *
     * @return Response object includes the current user / access key
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ApiResponse
     */
    @Operation(summary = "当前调用者", description = "返回当前用户与访问密钥标识", operationId = "whoami")
    @GetMapping("/whoami")
    fun whoami(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            mapOf(
                "userId" to pluginContext.currentUserId(),
                "accessKeyId" to pluginContext.currentAccessKeyId()
            )
        )
}
