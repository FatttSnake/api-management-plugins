package com.example.filebox

import javax.sql.DataSource

/**
 * Filebox schema
 *
 * Creates the plugin's own tables, one per datasource it declares. The DDL runs on both
 * database types the gateway offers, so it stays portable if a datasource is re-pointed:
 * identifiers are plugin-generated, times are epoch milliseconds, and every statement is
 * `create table if not exists` with no index.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
object FileboxSchema {
    /**
     * Files the plugin stored, one row per location-addressed object
     */
    private const val CREATE_FILE_TABLE = """
        create table if not exists t_p_filebox_file
        (
            id          varchar(36)  not null primary key,
            owner_id    bigint       not null,
            name        varchar(255) not null,
            size        bigint       not null,
            path        varchar(512) not null,
            create_time bigint       not null
        )
    """

    /**
     * Snapshots the plugin stored, keyed by content so identical text shares one row
     */
    private const val CREATE_SNAPSHOT_TABLE = """
        create table if not exists t_p_filebox_snapshot
        (
            content_key varchar(64) not null primary key,
            owner_id    bigint      not null,
            size        bigint      not null,
            create_time bigint      not null
        )
    """

    /**
     * Drop the file table
     */
    private const val DROP_FILE_TABLE = "drop table if exists t_p_filebox_file"

    /**
     * Drop the snapshot table
     */
    private const val DROP_SNAPSHOT_TABLE = "drop table if exists t_p_filebox_snapshot"

    /**
     * Create the file table when it is missing
     *
     * Runs on every start, so a datasource configured after install is picked up when the
     * remount brings it up again. A failure propagates, failing the mount and landing in the
     * plugin's `load_error`.
     *
     * @param dataSource The plugin's file datasource, or null while it is unconfigured
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun ensureFiles(dataSource: DataSource?) = dataSource?.let { execute(it, CREATE_FILE_TABLE) }

    /**
     * Create the snapshot table when it is missing
     *
     * @param dataSource The plugin's cache datasource, or null when the gateway supplied none
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see ensureFiles
     */
    fun ensureSnapshots(dataSource: DataSource?) = dataSource?.let { execute(it, CREATE_SNAPSHOT_TABLE) }

    /**
     * Drop what this plugin created, on an uninstall that asked for its data to be deleted
     *
     * @param files The plugin's file datasource, or null while it is unconfigured
     * @param snapshots The plugin's snapshot datasource, or null when the gateway supplied none
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun dropAll(files: DataSource?, snapshots: DataSource?) {
        files?.let { execute(it, DROP_FILE_TABLE) }
        snapshots?.let { execute(it, DROP_SNAPSHOT_TABLE) }
    }

    /**
     * Run one statement on a datasource
     */
    private fun execute(dataSource: DataSource, ddl: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(ddl)
            }
        }
    }
}
