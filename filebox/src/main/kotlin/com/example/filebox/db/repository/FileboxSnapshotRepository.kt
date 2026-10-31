package com.example.filebox.db.repository

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.example.filebox.db.entity.FileboxSnapshotEntity
import com.example.filebox.db.entity.toEntity
import com.example.filebox.db.entity.toModel
import com.example.filebox.db.mapper.FileboxSnapshotMapper
import com.example.filebox.model.FileboxSnapshot
import org.apache.ibatis.session.SqlSessionFactory

/**
 * Reads and writes the snapshot rows.
 *
 * @param sessions session factory of the snapshot datasource
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class FileboxSnapshotRepository(private val sessions: SqlSessionFactory) {
    /**
     * Inserts one snapshot row.
     *
     * @param snapshot snapshot metadata
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun insert(snapshot: FileboxSnapshot) = session { insert(snapshot.toEntity()) }

    /**
     * Finds a snapshot by its content key.
     *
     * @param contentKey content key
     * @return snapshot metadata, or null when it does not exist
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun find(contentKey: String): FileboxSnapshot? = session { selectById(contentKey)?.toModel() }

    /**
     * Every content key this plugin's snapshots reference.
     *
     * Read on purge so the plugin can release the content-addressed objects it stored: they are
     * shared between writers, so only the plugin can say which are its own.
     *
     * @return content keys, in no particular order
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun contentKeys(): List<String> = session {
        val query = KtQueryWrapper(FileboxSnapshotEntity::class.java)
            .select(FileboxSnapshotEntity::contentKey)

        selectList(query).map(FileboxSnapshotEntity::contentKey)
    }

    private inline fun <T> session(block: FileboxSnapshotMapper.() -> T): T =
        sessions.openSession(true).use { sqlSession ->
            block(sqlSession.getMapper(FileboxSnapshotMapper::class.java))
        }
}
