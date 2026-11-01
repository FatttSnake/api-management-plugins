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
import top.fatweb.apimanagement.api.v1.ResponseSpec
import top.fatweb.apimanagement.api.v1.RouteDefinition
import top.fatweb.apimanagement.api.v1.SchemaSpec

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
                    ),
                    responses = successResponse(
                        fileSchema(),
                        fileExample(),
                        "<1000>参数无效 <1001>未登录 <2004>上传令牌不匹配 <2005>文件过大 <5001>数据源未配置"
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
                    ),
                    responses = successResponse(
                        filePageSchema(),
                        filePageExample(),
                        "<1000>参数无效 <1001>未登录 <5001>数据源未配置"
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
                    listOf(fileIdParameter()),
                    responses = downloadResponse()
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
                    ),
                    responses = successResponse(
                        linkSchema(),
                        linkExample(),
                        "<1000>参数无效 <1001>未登录 <2001>文件不存在 <2002>无权访问 <5001>数据源未配置 <5002>外链不可用"
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
                    listOf(fileIdParameter()),
                    responses = successResponse(
                        removeSchema(),
                        removeExample(),
                        "<1000>参数无效 <1001>未登录 <2001>文件不存在 <2002>无权访问 <5001>数据源未配置"
                    )
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
                            example = EXAMPLE_TEXT
                        )
                    ),
                    responses = successResponse(
                        snapshotSchema(),
                        snapshotExample(),
                        "<1000>参数无效 <1001>未登录 <2003>快照未启用 <5001>数据源未配置"
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
                            example = EXAMPLE_CONTENT_KEY
                        )
                    ),
                    responses = successResponse(
                        readSnapshotSchema(),
                        readSnapshotExample(),
                        "<1000>参数无效 <1001>未登录 <2002>无权访问 <2006>快照不存在 <5001>数据源未配置"
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
                warning = LINK_WARNING
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

    private fun successResponse(
        data: SchemaSpec,
        dataExample: Any,
        failureCodes: String
    ): Map<String, ResponseSpec> = mapOf(
        "200" to ResponseSpec(
            description = "成功",
            schema = SchemaSpec.objectOf(
                properties = mapOf(
                    "code" to SchemaSpec.integer("业务码: <0>成功 $failureCodes", example = 0),
                    "success" to SchemaSpec.boolean("是否成功", example = true),
                    "msg" to SchemaSpec.string("提示信息", example = "success"),
                    "data" to data.copy(nullable = true)
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

    private fun downloadResponse(): Map<String, ResponseSpec> = mapOf(
        "200" to ResponseSpec(
            description = "成功",
            schema = SchemaSpec.string("文件内容", format = "binary"),
            mediaType = "application/octet-stream"
        )
    )

    private fun fileSchema(): SchemaSpec = SchemaSpec.objectOf(
        properties = mapOf(
            "id" to SchemaSpec.string("文件 ID", example = EXAMPLE_FILE_ID),
            "name" to SchemaSpec.string("原始文件名", example = EXAMPLE_FILE_NAME),
            "size" to SchemaSpec.integer("字节数", format = "int64", example = EXAMPLE_FILE_SIZE),
            "path" to SchemaSpec.string(
                "插件存储命名空间内的相对路径",
                example = "files/1/$EXAMPLE_FILE_ID.pdf"
            ),
            "createTime" to SchemaSpec.integer(
                "上传时间（epoch 毫秒）",
                format = "int64",
                example = EXAMPLE_CREATE_TIME
            )
        ),
        required = listOf("id", "name", "size", "path", "createTime")
    )

    private fun fileExample(): Map<String, Any> = mapOf(
        "id" to EXAMPLE_FILE_ID,
        "name" to EXAMPLE_FILE_NAME,
        "size" to EXAMPLE_FILE_SIZE,
        "path" to "files/1/$EXAMPLE_FILE_ID.pdf",
        "createTime" to EXAMPLE_CREATE_TIME
    )

    private fun filePageSchema(): SchemaSpec = SchemaSpec.objectOf(
        properties = mapOf(
            "records" to SchemaSpec.array(fileSchema(), "当前页文件"),
            "total" to SchemaSpec.integer("匹配的文件总数", format = "int64", example = 1),
            "page" to SchemaSpec.integer("页码，从 1 开始", format = "int64", example = 1),
            "size" to SchemaSpec.integer("每页条数", format = "int64", example = DEFAULT_PAGE_SIZE)
        ),
        required = listOf("records", "total", "page", "size")
    )

    private fun filePageExample(): Map<String, Any> = mapOf(
        "records" to listOf(fileExample()),
        "total" to 1L,
        "page" to 1L,
        "size" to DEFAULT_PAGE_SIZE
    )

    private fun linkSchema(): SchemaSpec = SchemaSpec.objectOf(
        properties = mapOf(
            "url" to SchemaSpec.string("免登外链", example = EXAMPLE_EXTERNAL_URL),
            "expiresInHours" to SchemaSpec.integer("外链有效期（小时）", format = "int64", example = 24),
            "notice" to SchemaSpec.string(
                "分享提示语，未配置时为 null",
                example = EXAMPLE_NOTICE,
                nullable = true
            ),
            "warning" to SchemaSpec.string("外链凭证提醒", example = LINK_WARNING)
        ),
        required = listOf("url", "expiresInHours", "warning")
    )

    private fun linkExample(): Map<String, Any> = mapOf(
        "url" to EXAMPLE_EXTERNAL_URL,
        "expiresInHours" to 24L,
        "notice" to EXAMPLE_NOTICE,
        "warning" to LINK_WARNING
    )

    private fun removeSchema(): SchemaSpec = SchemaSpec.objectOf(
        properties = mapOf(
            "id" to SchemaSpec.string("被删除的文件 ID", example = EXAMPLE_FILE_ID),
            "deleted" to SchemaSpec.boolean("是否已删除", example = true)
        ),
        required = listOf("id", "deleted")
    )

    private fun removeExample(): Map<String, Any> = mapOf(
        "id" to EXAMPLE_FILE_ID,
        "deleted" to true
    )

    private fun snapshotSchema(): SchemaSpec = SchemaSpec.objectOf(
        properties = mapOf(
            "contentKey" to SchemaSpec.string("内容 SHA-256，同时也是存储键", example = EXAMPLE_CONTENT_KEY),
            "size" to SchemaSpec.integer("内容字节数", format = "int64", example = 5),
            "deduplicated" to SchemaSpec.boolean("内容此前是否已存在", example = false)
        ),
        required = listOf("contentKey", "size", "deduplicated")
    )

    private fun snapshotExample(): Map<String, Any> = mapOf(
        "contentKey" to EXAMPLE_CONTENT_KEY,
        "size" to 5L,
        "deduplicated" to false
    )

    private fun readSnapshotSchema(): SchemaSpec = SchemaSpec.objectOf(
        properties = mapOf(
            "contentKey" to SchemaSpec.string("内容 SHA-256", example = EXAMPLE_CONTENT_KEY),
            "text" to SchemaSpec.string("快照文本", example = EXAMPLE_TEXT)
        ),
        required = listOf("contentKey", "text")
    )

    private fun readSnapshotExample(): Map<String, Any> = mapOf(
        "contentKey" to EXAMPLE_CONTENT_KEY,
        "text" to EXAMPLE_TEXT
    )

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
            example = EXAMPLE_FILE_ID
        )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20L
        const val MAX_PAGE_SIZE = 100L

        const val EXAMPLE_FILE_ID = "5d8f3c21-9a4e-4b7d-8c16-e2a7b9d05f43"
        const val EXAMPLE_FILE_NAME = "report.pdf"
        const val EXAMPLE_FILE_SIZE = 1024L
        const val EXAMPLE_CREATE_TIME = 1767225600000L
        const val EXAMPLE_NOTICE = "仅限内部流转"
        const val EXAMPLE_CONTENT_KEY =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        const val EXAMPLE_TEXT = "hello"
        const val EXAMPLE_EXTERNAL_URL =
            "https://files.example.com/public/storage/filebox/files/1/$EXAMPLE_FILE_ID.pdf" +
                "?e=1767229200&s=abc123"
        const val LINK_WARNING = "外链是免登凭证，持有者在过期前均可访问，且无法提前吊销"
    }
}
