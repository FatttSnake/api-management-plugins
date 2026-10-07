package com.example.filebox

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.multipart.MultipartFile
import top.fatweb.apimanagement.sdk.annotation.ApiController
import top.fatweb.apimanagement.sdk.plugin.ApiResponse
import top.fatweb.apimanagement.sdk.plugin.PluginContext
import java.nio.charset.StandardCharsets

/**
 * Filebox controller
 *
 * Every endpoint is exposed by the gateway as `/api/filebox/v1/...`, behind the platform's
 * authentication, rate limiting and billing. The operationId of each method is what names
 * the API scope code the administrator grants, e.g. `api:filebox:v1:upload`.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see PluginContext
 * @see FileboxService
 */
@ApiController(
    plugin = "filebox",
    version = 1
)
class FileboxController(
    private val pluginContext: PluginContext,
    private val fileboxService: FileboxService
) {
    /**
     * Upload a file
     *
     * @param file File content
     * @param token Shared upload token, required only when one is configured
     * @return Response object includes the stored file
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ApiResponse
     */
    @Operation(
        summary = "上传文件",
        description = "存入插件自己的文件区（位置寻址），元数据写入插件独立数据源",
        operationId = "upload"
    )
    @PostMapping("/upload")
    fun upload(
        @RequestPart("file") file: MultipartFile,
        @RequestParam("token", required = false) token: String?
    ): ApiResponse<Map<String, Any?>> = reply {
        val ownerId = requireUser()
        fileboxService.verifyUploadToken(token)

        ApiResponse.ok(fileboxService.upload(ownerId, file.originalFilename ?: "file", file.bytes).toMap())
    }

    /**
     * List the files of the current caller
     *
     * @return Response object includes the files
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ApiResponse
     */
    @Operation(summary = "文件列表", description = "查询插件独立数据源中当前调用者的文件", operationId = "list")
    @GetMapping("/files")
    fun list(): ApiResponse<List<Map<String, Any?>>> = reply {
        val ownerId = requireUser()

        ApiResponse.ok(fileboxService.list(ownerId).map(FileboxFile::toMap))
    }

    /**
     * Download a file
     *
     * The bytes are returned as they are, not wrapped in the response envelope, which is
     * the pass-through the SDK allows for an endpoint whose payload is the payload. A
     * failure is reported through the gateway's own error envelope instead.
     *
     * @param id File ID
     * @return File content
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ResponseEntity
     * @see ByteArray
     */
    @Operation(summary = "下载文件", description = "读回原样字节（位置寻址的对象不做压缩）", operationId = "download")
    @GetMapping("/files/{id}", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun download(@PathVariable id: String): ResponseEntity<ByteArray> {
        val ownerId = pluginContext.currentUserId()
            ?: throw FileboxException(FileboxCode.UNAUTHENTICATED, "未登录")
        val (file, bytes) = fileboxService.download(ownerId, id)

        return ResponseEntity.ok()
            .contentType(MediaTypeFactory.getMediaType(file.name).orElse(MediaType.APPLICATION_OCTET_STREAM))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.name, StandardCharsets.UTF_8).build().toString()
            )
            .contentLength(bytes.size.toLong())
            .body(bytes)
    }

    /**
     * Mint a login-free link to a file
     *
     * @param id File ID
     * @param ttlHours Link life in hours, or null for the configured default
     * @return Response object includes the link
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ApiResponse
     */
    @Operation(summary = "生成外链", description = "免登访问链接，未传有效期时使用配置项默认值", operationId = "link")
    @GetMapping("/files/{id}/link")
    fun link(
        @PathVariable id: String,
        @RequestParam(name = "ttlHours", required = false) ttlHours: Long?
    ): ApiResponse<Map<String, Any?>> = reply {
        val ownerId = requireUser()
        val (url, hours) = fileboxService.externalUrl(ownerId, id, ttlHours)

        ApiResponse.ok(
            mapOf(
                "url" to url,
                "expiresInHours" to hours,
                "notice" to fileboxService.publicNotice(),
                "warning" to "外链是免登凭证，持有者在过期前均可访问，且无法提前吊销"
            )
        )
    }

    /**
     * Delete a file
     *
     * @param id File ID
     * @return Response object
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ApiResponse
     */
    @Operation(summary = "删除文件", description = "删除元数据与原样字节，已发出的外链随之失效", operationId = "remove")
    @DeleteMapping("/files/{id}")
    fun remove(@PathVariable id: String): ApiResponse<Map<String, Any?>> = reply {
        val ownerId = requireUser()
        fileboxService.delete(ownerId, id)

        ApiResponse.ok(mapOf("id" to id, "deleted" to true))
    }

    /**
     * Store a text snapshot
     *
     * @param text Text content
     * @return Response object includes the content key
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ApiResponse
     */
    @Operation(
        summary = "保存文本快照",
        description = "内容寻址存储，相同内容只存一份（响应中的 deduplicated 表示命中了已有对象）",
        operationId = "snapshot"
    )
    @PostMapping("/snapshots")
    fun snapshot(@RequestParam("text") text: String): ApiResponse<Map<String, Any?>> = reply {
        val ownerId = requireUser()
        val result = fileboxService.snapshot(ownerId, text)

        ApiResponse.ok(
            mapOf(
                "contentKey" to result.contentKey,
                "size" to result.size,
                "deduplicated" to result.deduplicated
            )
        )
    }

    /**
     * Read a text snapshot
     *
     * @param contentKey Content key
     * @return Response object includes the text
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ApiResponse
     */
    @Operation(summary = "读取文本快照", description = "按内容 SHA-256 读回文本", operationId = "readSnapshot")
    @GetMapping("/snapshots/{contentKey}")
    fun readSnapshot(@PathVariable contentKey: String): ApiResponse<Map<String, Any?>> = reply {
        val ownerId = requireUser()

        ApiResponse.ok(
            mapOf(
                "contentKey" to contentKey,
                "text" to fileboxService.readSnapshot(ownerId, contentKey)
            )
        )
    }

    /**
     * Resolve the current caller
     *
     * A call reaches a plugin only after the gateway has authenticated it, so this is
     * about tying the plugin's own data to that caller rather than about access control.
     */
    private fun requireUser(): Long = pluginContext.currentUserId()
        ?: throw FileboxException(FileboxCode.UNAUTHENTICATED, "未登录")

    /**
     * Answer a business failure with its own code instead of letting it escape
     */
    private fun <T> reply(block: () -> ApiResponse<T>): ApiResponse<T> =
        try {
            block()
        } catch (e: FileboxException) {
            ApiResponse.fail(e.code, e.message ?: "文件盒处理失败")
        }
}
