package com.example.filebox

import top.fatweb.apimanagement.api.v1.ApiPlugin
import top.fatweb.apimanagement.api.v1.GatewayContext

/**
 * Filebox plugin.
 *
 * Composes one runtime per mount, registers the file and snapshot routes on start, and releases
 * plugin-owned data on purge.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class FileboxPlugin : ApiPlugin {
    // Null while a mount that failed before its runtime existed is being torn down
    private var runtime: FileboxRuntime? = null

    override fun onStart(context: GatewayContext) {
        val mounted = FileboxRuntime.create(context)
        mounted.registerRoutes()
        mounted.started()
        runtime = mounted
    }

    /**
     * Logs the keys an administrator changed.
     *
     * Ordinary config values are read per call, so no runtime rebuild is needed; a datasource
     * change remounts the plugin instead of reaching here.
     *
     * @param context gateway capabilities for this plugin
     * @param changedKeys declared keys whose effective value changed
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    override fun onConfigChanged(context: GatewayContext, changedKeys: Set<String>) {
        context.logger().info("Filebox configuration changed: {}", changedKeys.sorted().joinToString())
    }

    override fun onStop() {
        runtime?.stopped()
    }

    override fun onUninstall() {
        runtime?.uninstalled()
    }

    override fun onPurge() {
        runtime?.purge()
    }
}
