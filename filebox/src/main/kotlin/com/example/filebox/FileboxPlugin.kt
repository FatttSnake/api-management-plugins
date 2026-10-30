package com.example.filebox

import top.fatweb.apimanagement.api.v1.ApiException
import top.fatweb.apimanagement.api.v1.ApiPlugin
import top.fatweb.apimanagement.api.v1.ApiRequest
import top.fatweb.apimanagement.api.v1.ApiResponse
import top.fatweb.apimanagement.api.v1.GatewayContext
import top.fatweb.apimanagement.api.v1.OperationMetadata
import top.fatweb.apimanagement.api.v1.ParameterLocation
import top.fatweb.apimanagement.api.v1.ParameterSpec
import top.fatweb.apimanagement.api.v1.RouteDefinition

/**
 * Filebox plugin.
 *
 * Registers the file and snapshot routes on start and releases plugin-owned data on purge.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class FileboxPlugin : ApiPlugin {
    private lateinit var context: GatewayContext
    private lateinit var service: FileboxService

    override fun onStart(context: GatewayContext) {
        this.context = context
        this.service = FileboxService(context)

        FileboxSchema.ensureFiles(context.dataSources().get(MAIN))
        FileboxSchema.ensureSnapshots(context.dataSources().get(CACHE))
        context.settings().save("startedAt", System.currentTimeMillis().toString())

        context.api().register(
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
        context.api().register(
            RouteDefinition(
                "GET",
                "/files",
                1,
                OperationMetadata("list", "文件列表", "查询插件独立数据源中当前调用者的文件")
            ),
            ::list
        )
        context.api().register(
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
        context.api().register(
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
        context.api().register(
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
        context.api().register(
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
        context.api().register(
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

    override fun onStop() {
        context.settings().save("stoppedAt", System.currentTimeMillis().toString())
    }

    override fun onUninstall() {
        context.settings().save("uninstalledAt", System.currentTimeMillis().toString())
    }

    override fun onPurge() {
        context.dataSources().get(CACHE)?.let { cache ->
            FileboxRepository(cache).listContentKeys().forEach { context.storage().deleteContent(it) }
        }
        FileboxSchema.dropAll(context.dataSources().get(MAIN), context.dataSources().get(CACHE))
        context.settings().save("purgedAt", System.currentTimeMillis().toString())
    }

    private fun upload(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        service.verifyUploadToken(request.queryParam("token"))
        val fileName = request.queryParam("fileName") ?: "file"

        return ApiResponse.ok(service.upload(ownerId, fileName, request.body).toResponse())
    }

    private fun list(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)

        return ApiResponse.ok(service.list(ownerId).map(FileboxFile::toResponse))
    }

    private fun download(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        val id = request.pathParam("id")
            ?: throw ApiException(FileboxCode.INVALID_ARGUMENT, "缺少文件 ID")
        val (file, bytes) = service.download(ownerId, id)

        return ApiResponse.raw(200, "application/octet-stream", bytes)
            .withHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
    }

    private fun link(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        val id = request.pathParam("id")
            ?: throw ApiException(FileboxCode.INVALID_ARGUMENT, "缺少文件 ID")
        val ttlHours = request.queryParam("ttlHours")?.toLongOrNull()
        val (url, hours) = service.externalUrl(ownerId, id, ttlHours)

        return ApiResponse.ok(
            LinkResponse(
                url = url,
                expiresInHours = hours,
                notice = service.publicNotice(),
                warning = "外链是免登凭证，持有者在过期前均可访问，且无法提前吊销"
            )
        )
    }

    private fun remove(request: ApiRequest): ApiResponse {
        val ownerId = requireUser(request)
        val id = request.pathParam("id")
            ?: throw ApiException(FileboxCode.INVALID_ARGUMENT, "缺少文件 ID")
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
        const val MAIN = "main"
        const val CACHE = "cache"
    }
}
