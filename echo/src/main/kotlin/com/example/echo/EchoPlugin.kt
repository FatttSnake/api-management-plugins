package com.example.echo

import top.fatweb.apimanagement.api.v1.*

/**
 * Echo plugin.
 *
 * Demonstrates route registration at two API versions, operation metadata and the lifecycle hooks.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class EchoPlugin : ApiPlugin {
    private lateinit var settings: Settings

    override fun onStart(context: GatewayContext) {
        settings = context.settings()
        settings.save("startedAt", System.currentTimeMillis().toString())

        val messageParameter = ParameterSpec(
            name = "message",
            location = ParameterLocation.QUERY,
            type = "string",
            required = false,
            description = "回声消息，省略时返回 pong",
            example = "hello"
        )

        context.api().register(
            RouteDefinition(
                "GET",
                "/ping",
                1,
                OperationMetadata(
                    operationId = "ping",
                    summary = "回声",
                    description = "返回回声消息与当前调用者",
                    parameters = listOf(messageParameter),
                    responses = response(
                        data = SchemaSpec.objectOf(
                            properties = mapOf(
                                "message" to SchemaSpec.string("回声消息", example = "hello"),
                                "userId" to SchemaSpec.integer(
                                    description = "当前用户 ID",
                                    format = "int64",
                                    example = 1,
                                    nullable = true
                                )
                            ),
                            required = listOf("message")
                        ),
                        dataExample = mapOf("message" to "hello", "userId" to 1)
                    )
                )
            )
        ) { request ->
            ApiResponse.ok(PingResponse(request.queryParam("message") ?: "pong", request.principal?.userId))
        }
        context.api().register(
            RouteDefinition(
                "GET",
                "/version",
                1,
                OperationMetadata(
                    operationId = "version",
                    summary = "版本信息",
                    description = "返回插件 API 版本与端点列表",
                    responses = response(
                        data = SchemaSpec.objectOf(
                            properties = mapOf(
                                "version" to SchemaSpec.integer("API 版本", example = 1),
                                "apis" to SchemaSpec.array(
                                    items = SchemaSpec.string(),
                                    description = "端点列表"
                                )
                            ),
                            required = listOf("version", "apis")
                        ),
                        dataExample = mapOf("version" to 1, "apis" to listOf("ping", "version"))
                    )
                )
            )
        ) {
            ApiResponse.ok(VersionResponse(1, listOf("ping", "version")))
        }
        context.api().register(
            RouteDefinition(
                "GET",
                "/ping",
                2,
                OperationMetadata(
                    operationId = "ping",
                    summary = "回声",
                    description = "返回 v2 回声消息、版本信息与当前调用者",
                    parameters = listOf(messageParameter),
                    responses = response(
                        data = SchemaSpec.objectOf(
                            properties = mapOf(
                                "message" to SchemaSpec.string("回声消息", example = "hello"),
                                "version" to SchemaSpec.integer("API 版本", example = 2),
                                "userId" to SchemaSpec.integer(
                                    description = "当前用户 ID",
                                    format = "int64",
                                    example = 1,
                                    nullable = true
                                )
                            ),
                            required = listOf("message", "version")
                        ),
                        dataExample = mapOf("message" to "hello", "version" to 2, "userId" to 1)
                    )
                )
            )
        ) { request ->
            ApiResponse.ok(
                PingV2Response(
                    request.queryParam("message") ?: "pong",
                    2,
                    request.principal?.userId
                )
            )
        }
        context.api().register(
            RouteDefinition(
                "GET",
                "/whoami",
                2,
                OperationMetadata(
                    operationId = "whoami",
                    summary = "当前调用者",
                    description = "返回当前用户与访问密钥标识",
                    responses = response(
                        data = SchemaSpec.objectOf(
                            properties = mapOf(
                                "userId" to SchemaSpec.integer(
                                    description = "当前用户 ID",
                                    format = "int64",
                                    example = 1,
                                    nullable = true
                                ),
                                "accessKeyId" to SchemaSpec.integer(
                                    description = "访问密钥 ID",
                                    format = "int64",
                                    example = 1,
                                    nullable = true
                                )
                            )
                        ),
                        dataExample = mapOf("userId" to 1, "accessKeyId" to 1)
                    )
                )
            )
        ) { request ->
            ApiResponse.ok(
                WhoamiResponse(
                    userId = request.principal?.userId,
                    accessKeyId = request.principal?.accessKeyId
                )
            )
        }
    }

    override fun onStop() {
        settings.save("stoppedAt", System.currentTimeMillis().toString())
    }

    override fun onUninstall() {
        settings.save("uninstalledAt", System.currentTimeMillis().toString())
    }

    private fun response(data: SchemaSpec, dataExample: Any): Map<String, ResponseSpec> =
        mapOf(
            "200" to ResponseSpec(
                description = "成功",
                schema = SchemaSpec.objectOf(
                    properties = mapOf(
                        "code" to SchemaSpec.integer("业务码", example = 0),
                        "success" to SchemaSpec.boolean("是否成功", example = true),
                        "msg" to SchemaSpec.string("提示信息", example = "success"),
                        "data" to data
                    ),
                    required = listOf("code", "success", "msg", "data")
                ),
                example = mapOf(
                    "code" to 0,
                    "success" to true,
                    "msg" to "success",
                    "data" to dataExample
                )
            )
        )
}
