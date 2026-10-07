package com.example.filebox

import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Filebox file metadata
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class FileboxFile(
    /**
     * Plugin-generated file ID
     */
    val id: String,

    /**
     * ID of the user the file belongs to
     */
    val ownerId: Long,

    /**
     * Original file name, as uploaded
     */
    val name: String,

    /**
     * Size in bytes
     */
    val size: Long,

    /**
     * Path of the bytes inside the plugin's own storage namespace
     */
    val path: String,

    /**
     * Upload time, in epoch milliseconds
     */
    val createTime: Long
) {
    /**
     * Build the API representation
     *
     * @return File fields
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "size" to size,
        "path" to path,
        "createTime" to createTime
    )
}

/**
 * Filebox snapshot metadata
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class FileboxSnapshot(
    /**
     * SHA-256 of the content, which is also its storage key
     */
    val contentKey: String,

    /**
     * ID of the user who stored the content first
     */
    val ownerId: Long,

    /**
     * Size in bytes
     */
    val size: Long,

    /**
     * Store time, in epoch milliseconds
     */
    val createTime: Long
)

/**
 * Filebox repository
 *
 * Plain JDBC against the plugin's own datasource. The gateway's MyBatis mappers are not
 * visible to a plugin, and it should not want them: these tables belong to the plugin
 * alone, which is what lets it migrate them whenever it likes.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 * @see DataSource
 */
class FileboxRepository(private val dataSource: DataSource) {
    /**
     * Insert the metadata of an uploaded file
     *
     * @param file File metadata
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see FileboxFile
     */
    fun insertFile(file: FileboxFile) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into t_p_filebox_file (id, owner_id, name, size, path, create_time) values (?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, file.id)
                statement.setLong(2, file.ownerId)
                statement.setString(3, file.name)
                statement.setLong(4, file.size)
                statement.setString(5, file.path)
                statement.setLong(6, file.createTime)
                statement.executeUpdate()
            }
        }
    }

    /**
     * List the files of a user, newest first
     *
     * @param ownerId Owner ID
     * @return File metadata
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see FileboxFile
     */
    fun listFiles(ownerId: Long): List<FileboxFile> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select id, owner_id, name, size, path, create_time from t_p_filebox_file where owner_id = ?"
                    + " order by create_time desc"
            ).use { statement ->
                statement.setLong(1, ownerId)
                statement.executeQuery().use { result ->
                    return buildList {
                        while (result.next()) {
                            add(result.toFile())
                        }
                    }
                }
            }
        }
    }

    /**
     * Find a file by its ID
     *
     * @param id File ID
     * @return File metadata, or null when it does not exist
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see FileboxFile
     */
    fun findFile(id: String): FileboxFile? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select id, owner_id, name, size, path, create_time from t_p_filebox_file where id = ?"
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toFile() else null
                }
            }
        }
    }

    /**
     * Delete a file by its ID
     *
     * @param id File ID
     * @return true=deleted; false=not found
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun deleteFile(id: String): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement("delete from t_p_filebox_file where id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate() > 0
            }
        }

    /**
     * Insert the metadata of a snapshot if the content is not recorded yet
     *
     * Identical content is stored once, so a second writer of the same bytes leaves the
     * existing row alone rather than replacing its owner.
     *
     * @param snapshot Snapshot metadata
     * @return true=inserted; false=the content was already recorded
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see FileboxSnapshot
     */
    fun insertSnapshot(snapshot: FileboxSnapshot): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into t_p_filebox_snapshot (content_key, owner_id, size, create_time) values (?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, snapshot.contentKey)
                statement.setLong(2, snapshot.ownerId)
                statement.setLong(3, snapshot.size)
                statement.setLong(4, snapshot.createTime)
                statement.executeUpdate() > 0
            }
        }

    /**
     * Find a snapshot by its content key
     *
     * @param contentKey Content key
     * @return Snapshot metadata, or null when it does not exist
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     * @see FileboxSnapshot
     */
    fun findSnapshot(contentKey: String): FileboxSnapshot? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select content_key, owner_id, size, create_time from t_p_filebox_snapshot where content_key = ?"
            ).use { statement ->
                statement.setString(1, contentKey)
                statement.executeQuery().use { result ->
                    return if (result.next()) {
                        FileboxSnapshot(
                            contentKey = result.getString("content_key"),
                            ownerId = result.getLong("owner_id"),
                            size = result.getLong("size"),
                            createTime = result.getLong("create_time")
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * Every content key this plugin's snapshots reference
     *
     * Read when the plugin is purged, to give back the content-addressed objects it stored.
     * Those are shared between every writer of the same bytes and belong to no plugin, so the
     * gateway has no way to tell which of them a plugin put there - only the plugin does, and
     * this is where it knows.
     *
     * @return Content keys, in no particular order
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun listContentKeys(): List<String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement("select content_key from t_p_filebox_snapshot").use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(result.getString("content_key"))
                        }
                    }
                }
            }
        }

    private fun ResultSet.toFile() = FileboxFile(
        id = getString("id"),
        ownerId = getLong("owner_id"),
        name = getString("name"),
        size = getLong("size"),
        path = getString("path"),
        createTime = getLong("create_time")
    )
}
