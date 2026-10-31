package com.example.filebox.db

import javax.sql.DataSource

/**
 * Drops the tables the filebox plugin owns.
 *
 * Flyway's `clean` is deliberately not used: the MySQL database is the administrator's and may
 * hold tables that are not the plugin's. Only the plugin's own objects and its Flyway history
 * are dropped, which leaves the schema empty for a fresh migration after a reinstall.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
object FileboxPurge {
    private const val DROP_FILE = "drop table if exists t_p_filebox_file"
    private const val DROP_SNAPSHOT = "drop table if exists t_p_filebox_snapshot"
    private const val DROP_HISTORY = "drop table if exists ${FileboxDatabase.HISTORY_TABLE}"

    /**
     * Drops the file rows and the snapshot rows.
     *
     * @param files files datasource, or null while it is unconfigured
     * @param cache snapshot datasource, or null when the gateway supplied none
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun run(files: DataSource?, cache: DataSource?) {
        files?.let {
            execute(it, DROP_FILE)
            execute(it, DROP_HISTORY)
        }
        cache?.let {
            execute(it, DROP_SNAPSHOT)
            execute(it, DROP_HISTORY)
        }
    }

    private fun execute(dataSource: DataSource, ddl: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(ddl) }
        }
    }
}
