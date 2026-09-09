package com.example.echo

import org.springframework.stereotype.Component
import top.fatweb.apimanagement.sdk.plugin.PluginContext
import top.fatweb.apimanagement.sdk.plugin.PluginLifecycle

/**
 * Echo plugin lifecycle
 *
 * Demonstrates the plugin lifecycle hooks; persisted settings can be read back via
 * [PluginContext.getSetting].
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see PluginLifecycle
 * @see PluginContext
 */
@Component
class EchoLifecycle : PluginLifecycle {
    override fun onStart(context: PluginContext) {
        context.saveSetting("startedAt", System.currentTimeMillis().toString())
    }

    override fun onStop(context: PluginContext) {
        context.saveSetting("stoppedAt", System.currentTimeMillis().toString())
    }

    override fun onUninstall(context: PluginContext) {
        context.saveSetting("uninstalledAt", System.currentTimeMillis().toString())
    }
}
