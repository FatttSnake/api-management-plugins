package com.example.filebox

import org.springframework.stereotype.Component
import top.fatweb.apimanagement.sdk.plugin.PluginContext
import top.fatweb.apimanagement.sdk.plugin.PluginLifecycle

/**
 * Filebox lifecycle
 *
 * Prepares the plugin's tables whenever it is mounted, drops them when an administrator
 * uninstalls it asking for its data to be deleted, and records when each hook ran. Those
 * timestamps are written with `saveSetting`, which is the plugin's own runtime space: the
 * keys are not declared by the plugin's config schema, so they stay writable by the plugin
 * - unlike the settings an administrator owns, which `saveSetting` refuses.
 *
 * `main` is absent while its connection has not been filled in, so [onStart] runs again on
 * every mount rather than only on the first: saving that connection remounts the plugin,
 * and the tables have to be there when it comes back up.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see PluginLifecycle
 */
@Component
class FileboxLifecycle : PluginLifecycle {
    private companion object {
        /**
         * Datasource holding the files themselves
         */
        const val MAIN = "main"

        /**
         * Datasource holding the text snapshots
         */
        const val CACHE = "cache"
    }

    override fun onStart(context: PluginContext) {
        ensureSchema(context)
        context.saveSetting("startedAt", System.currentTimeMillis().toString())
    }

    override fun onStop(context: PluginContext) {
        context.saveSetting("stoppedAt", System.currentTimeMillis().toString())
    }

    override fun onUninstall(context: PluginContext) {
        context.saveSetting("uninstalledAt", System.currentTimeMillis().toString())
    }

    /**
     * Drop what this plugin created, for an uninstall that asked for its data to be deleted
     *
     * `onUninstall` cannot do this: by the time it runs the datasources are closed with the
     * rest of the mount. This runs while they are still open and nothing is calling in, which
     * is what makes dropping the tables - including in the external MySQL the gateway never
     * touches - possible at all.
     *
     * It also gives back the content-addressed objects the snapshots reference. Those are
     * shared between every writer of the same bytes and belong to no plugin, so the gateway
     * never reclaims them on a plugin's behalf; releasing them is the plugin's own business,
     * and this is the only moment it is both asked to and still able to.
     */
    override fun onPurge(context: PluginContext) {
        context.datasources[CACHE]?.let { cache ->
            FileboxRepository(cache).listContentKeys().forEach { context.storage.deleteContent(it) }
        }
        FileboxSchema.dropAll(context.datasources[MAIN], context.datasources[CACHE])
        context.saveSetting("purgedAt", System.currentTimeMillis().toString())
    }

    /**
     * Prepare whatever each of the plugin's databases is meant to hold
     */
    private fun ensureSchema(context: PluginContext) {
        FileboxSchema.ensureFiles(context.datasources[MAIN])
        FileboxSchema.ensureSnapshots(context.datasources[CACHE])
    }
}
