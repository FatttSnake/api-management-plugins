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
