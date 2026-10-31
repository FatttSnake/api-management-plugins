package com.example.filebox.api

import com.example.filebox.config.FileboxSettings
import com.example.filebox.service.FileboxService
import top.fatweb.apimanagement.api.v1.ApiException
import top.fatweb.apimanagement.api.v1.ApiRequest
import top.fatweb.apimanagement.api.v1.ApiResponse
import top.fatweb.apimanagement.api.v1.ApiRoutes
import top.fatweb.apimanagement.api.v1.OperationMetadata
import top.fatweb.apimanagement.api.v1.ParameterLocation
import top.fatweb.apimanagement.api.v1.ParameterSpec
import top.fatweb.apimanagement.api.v1.RouteDefinition

/**
 * HTTP surface of the filebox plugin.
 *
 * Declares the routes and translates requests into service calls. Each handler owns the
 * authorization of the file it names; the gateway has already authenticated the caller.
 *
 * @param settings plugin settings
 * @param service filebox service
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class FileboxApi(private val settings: FileboxSettings, private val service: FileboxService) {
    /**
     * Registers every filebox route.
     *
     * @param routes route registry of the running plugin
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun register(routes: ApiRoutes) {
        routes.register(
            RouteDefinition(
                "POST",
                "/upload",
                1,
                OperationMetadata(
                    "upload",
                    "上传文件",
                    "存入插件自己的文件区（位置寻址），元数据写入插件独立数据源",
                    listOf(
                        ParameterSpec(
                            name = "fileName",
                            location = ParameterLocation.QUERY,
                            type = "string",
                            required = false,
                            description = "文件名，省略时为 file",
                            example = "report.pdf"
                        ),
                        ParameterSpec(
                            name = "token",
                            location = ParameterLocation.QUERY,
                            type = "string",
                            required = false,
                            description = "上传令牌，配置要求时必填",
                            example = "upload-token"
                        )
                    )
                )
            ),
            ::upload
        )
        routes.register(
            RouteDefinition(
                "GET",
                "/files",
                1,
                OperationMetadata(
                    "list",
                    "文件列表",
                    "分页查询插件独立数据源中当前调用者的文件",
                    listOf(
                        ParameterSpec.query("name", description = "文件名前缀", example = "report"),
                        ParameterSpec(
                            name = "page",
                            location = ParameterLocation.QUERY,
                            type = "integer",
                            description = "页码，从 1 开始",
                            format = "int64",
                            example = 1
                        ),
                        ParameterSpec(
                            name = "size",
                            location = ParameterLocation.QUERY,
                            type = "integer",
                            description = "每页条数，最大 $MAX_PAGE_SIZE",
                            format = "int64",
                            example = DEFAULT_PAGE_SIZE
                        )
                    )
                )
            ),
            ::list
        )
        routes.register(
            RouteDefinition(
                "GET",
                "/files/{id}",
                1,
                OperationMetadata(
                    "download",
                    "下载文件",
                    "读回原样字节（位置寻址的对象不做压缩）",
                    listOf(fileIdParameter())
                )
            ),
            ::download
        )
        routes.register(
            RouteDefinition(
                "GET",
                "/files/{id}/link",
                1,
                OperationMetadata(
                    "link",
                    "生成外链",
                    "免登访问链接，未传有效期时使用配置项默认值",
                    listOf(
                        fileIdParameter(),
                        ParameterSpec(
                            name = "ttlHours",
                            location = ParameterLocation.QUERY,
                            type = "integer",
                            required = false,
                            description = "外链有效期（小时），省略时使用配置默认值",
                            format = "int64",
                            example = 24
                        )
                    )
                )
            ),
            ::link
        )
        routes.register(
            RouteDefinition(
                "DELETE",
                "/files/{id}",
                1,
                OperationMetadata(
                    "remove",
                    "删除文件",
                    "删除元数据与原样字节，已发出的外链随之失效",
                    listOf(fileIdParameter())
                )
            ),
            ::remove
        )
        routes.register(
            RouteDefinition(
                "POST",
                "/snapshots",
                1,
                OperationMetadata(
                    "snapshot",
                    "保存文本快照",
                    "内容寻址存储，相同内容只存一份",
                    listOf(
                        ParameterSpec(
                            name = "text",
                            location = ParameterLocation.QUERY,
                            type = "string",
                            required = true,
                            description = "快照文本内容",
                            example = "hello"
                        )
                    )
                )
            ),
            ::snapshot
        )
        routes.register(
            RouteDefinition(
                "GET",
                "/snapshots/{contentKey}",
                1,
                OperationMetadata(
                    "readSnapshot",
                    "读取文本快照",
                    "按内容 SHA-256 读回文本",
                    listOf(
                        ParameterSpec(
                            name = "contentKey",
                            location = ParameterLocation.PATH,
                            type = "string",
                            required = true,
                            description = "内容 SHA-256",
                            example = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
                        )
                    )
                )
            ),
            ::readSnapshot
        )
    }

    private fun upload(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        settings.verifyUploadToken(request.queryParam("token"))
        val fileName = request.queryParam("fileName") ?: "file"

        return ApiResponse.ok(service.upload(ownerId, fileName, request.body).toResponse())
    }

    private fun list(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        val name = request.queryParam("name")?.trim()?.takeIf { it.isNotEmpty() }
        val page = positiveParam(request, "page", 1)
        val size = positiveParam(request, "size", DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE)

        return ApiResponse.ok(service.page(ownerId, name, page, size).toResponse())
    }

    private fun download(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        val id = requireId(request)
        val (file, bytes) = service.download(ownerId, id)

        return ApiResponse.raw(200, "application/octet-stream", bytes)
            .withHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
    }

    private fun link(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        val id = requireId(request)
        val ttlHours = request.queryParam("ttlHours")?.toLongOrNull()
        val (url, hours) = service.externalUrl(ownerId, id, ttlHours)

        return ApiResponse.ok(
            LinkResponse(
                url = url,
                expiresInHours = hours,
                notice = settings.publicNotice(),
                warning = "外链是免登凭证，持有者在过期前均可访问，且无法提前吊销"
            )
        )
    }

    private fun remove(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        val id = requireId(request)
        service.delete(ownerId, id)

        return ApiResponse.ok(RemoveResponse(id = id, deleted = true))
    }

    private fun snapshot(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        val text = request.queryParam("text")
            ?: throw ApiException(FileboxCode.INVALID_ARGUMENT, "缺少文本内容")

        return ApiResponse.ok(service.snapshot(ownerId, text).toResponse())
    }

    private fun readSnapshot(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        val contentKey = request.pathParam("contentKey")
            ?: throw ApiException(FileboxCode.INVALID_ARGUMENT, "缺少内容键")

        return ApiResponse.ok(
            ReadSnapshotResponse(
                contentKey = contentKey,
                text = service.readSnapshot(ownerId, contentKey)
            )
        )
    }

    private fun requireUser(request: ApiRequest): Long =
        request.principal?.userId
            ?: throw ApiException(FileboxCode.UNAUTHENTICATED, "未登录")

    private fun requireId(request: ApiRequest): String =
        request.pathParam("id")
            ?: throw ApiException(FileboxCode.INVALID_ARGUMENT, "缺少文件 ID")

    private fun positiveParam(
        request: ApiRequest,
        name: String,
        fallback: Long,
        maximum: Long = Long.MAX_VALUE
    ): Long {
        val raw = request.queryParam(name) ?: return fallback
        val value = raw.toLongOrNull()
            ?: throw ApiException(FileboxCode.INVALID_ARGUMENT, "参数 $name 必须是整数")
        if (value < 1) {
            throw ApiException(FileboxCode.INVALID_ARGUMENT, "参数 $name 必须大于 0")
        }
        if (value > maximum) {
            throw ApiException(FileboxCode.INVALID_ARGUMENT, "参数 $name 不能大于 $maximum")
        }

        return value
    }

    private fun fileIdParameter(): ParameterSpec =
        ParameterSpec(
            name = "id",
            location = ParameterLocation.PATH,
            type = "string",
            required = true,
            description = "文件 ID",
            example = "1"
        )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20L
        const val MAX_PAGE_SIZE = 100L
    }
}
