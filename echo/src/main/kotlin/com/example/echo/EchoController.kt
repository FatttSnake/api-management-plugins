package com.example.echo

import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import top.fatweb.apimanagement.sdk.annotation.ApiController
import top.fatweb.apimanagement.sdk.plugin.ApiResponse
import top.fatweb.apimanagement.sdk.plugin.PluginContext

/**
 * Echo controller (v1)
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see ApiController
 * @see PluginContext
 */
@ApiController(version = 1)
class EchoController(
    private val pluginContext: PluginContext,
    private val echoService: EchoService
) {
    /**
     * Echo
     *
     * @return Response object includes the echo message and the current user
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ApiResponse
     */
    @Operation(summary = "回声", description = "返回回声消息与当前调用者", operationId = "ping")
    @GetMapping("/ping")
    fun ping(): ApiResponse<PingResponse> =
        ApiResponse.ok(
            PingResponse(
                message = echoService.message(),
                userId = pluginContext.currentUserId()
            )
        )

    /**
     * Version information
     *
     * @return Response object includes the API version and endpoint list
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ApiResponse
     */
    @Operation(summary = "版本信息", description = "返回插件 API 版本与端点列表", operationId = "version")
    @GetMapping("/version")
    fun version(): ApiResponse<VersionResponse> =
        ApiResponse.ok(
            VersionResponse(
                version = 1,
                apis = listOf("ping", "version")
            )
        )
}
