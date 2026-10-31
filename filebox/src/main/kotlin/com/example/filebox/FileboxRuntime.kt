package com.example.filebox

import com.baomidou.mybatisplus.annotation.DbType
import com.example.filebox.api.FileboxApi
import com.example.filebox.config.FileboxSettings
import com.example.filebox.db.FileboxDatabase
import com.example.filebox.db.FileboxPurge
import com.example.filebox.service.FileboxService
import top.fatweb.apimanagement.api.v1.GatewayContext

/**
 * Composition root of one filebox mount.
 *
 * Migrates the declared datasources and wires their repositories into the service. Everything
 * built here belongs to one mount and is replaced when the plugin is remounted.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class FileboxRuntime private constructor(
    private val context: GatewayContext,
    private val settings: FileboxSettings,
    private val cache: FileboxDatabase?,
    private val service: FileboxService,
) {
    /**
     * Registers the plugin routes.
     *
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun registerRoutes() = FileboxApi(settings, service).register(context.api())

    /**
     * Records the mount time.
     *
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun started() = settings.started()

    /**
     * Records the unmount time.
     *
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun stopped() = settings.stopped()

    /**
     * Records the uninstall time.
     *
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun uninstalled() = settings.uninstalled()

    /**
     * Releases the content-addressed objects and drops the tables this plugin owns.
     *
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun purge() {
        cache?.snapshots?.contentKeys()?.forEach(context.storage()::deleteContent)
        FileboxPurge.run(context.dataSources().get(MAIN), context.dataSources().get(CACHE))
    }

    companion object {
        /**
         * Datasource holding the files themselves
         */
        const val MAIN = "main"

        /**
         * Datasource holding the text snapshots
         */
        const val CACHE = "cache"

        private const val FILE_MIGRATIONS = "classpath:db/migration/main"
        private const val SNAPSHOT_MIGRATIONS = "classpath:db/migration/cache"

        /**
         * Builds the runtime of one mount.
         *
         * @param context gateway context of this mount
         * @return runtime
         * @author FatttSnake, fatttsnake@gmail.com
         * @since 1.0.0
         */
        fun create(context: GatewayContext): FileboxRuntime {
            val main =
                context.dataSources().get(MAIN)?.let { dataSource ->
                    FileboxDatabase.open(dataSource, DbType.MYSQL, FILE_MIGRATIONS)
                }
            val cache =
                context.dataSources().get(CACHE)?.let { dataSource ->
                    FileboxDatabase.open(dataSource, DbType.SQLITE, SNAPSHOT_MIGRATIONS)
                }
            val settings = FileboxSettings(context.settings())

            return FileboxRuntime(
                context = context,
                settings = settings,
                cache = cache,
                service = FileboxService(settings, context.storage(), main?.files, cache?.snapshots),
            )
        }
    }
}
