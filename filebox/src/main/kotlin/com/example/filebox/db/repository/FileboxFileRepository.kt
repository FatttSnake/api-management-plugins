package com.example.filebox.db.repository

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.example.filebox.db.entity.FileboxFileEntity
import com.example.filebox.db.entity.toEntity
import com.example.filebox.db.entity.toModel
import com.example.filebox.db.mapper.FileboxFileMapper
import com.example.filebox.model.FileboxFile
import com.example.filebox.model.FileboxPage
import org.apache.ibatis.session.SqlSessionFactory

/**
 * Reads and writes the file rows.
 *
 * Each call opens an auto-commit session: a plugin has no Spring container to scope one for it,
 * and a pooled connection is what the datasource holds anyway.
 *
 * @param sessions session factory of the files datasource
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class FileboxFileRepository(private val sessions: SqlSessionFactory) {
    /**
     * Inserts one file row.
     *
     * @param file file metadata
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun insert(file: FileboxFile) = session { insert(file.toEntity()) }

    /**
     * Finds a file by its ID.
     *
     * @param id file ID
     * @return file metadata, or null when it does not exist
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun find(id: String): FileboxFile? = session { selectById(id)?.toModel() }

    /**
     * Lists the files of one user, newest first.
     *
     * Filtering and paging happen in the database.
     *
     * @param ownerId owner ID
     * @param name file name prefix, or null for every name
     * @param page page number, starting at 1
     * @param size page size
     * @return one page of file metadata
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun page(ownerId: Long, name: String?, page: Long, size: Long): FileboxPage<FileboxFile> = session {
        val query = KtQueryWrapper(FileboxFileEntity::class.java)
            .eq(FileboxFileEntity::ownerId, ownerId)
            .likeRight(name != null, FileboxFileEntity::name, name)
            .orderByDesc(FileboxFileEntity::createTime)
        val result = selectPage(Page(page, size), query)

        FileboxPage(
            records = result.records.map(FileboxFileEntity::toModel),
            total = result.total,
            page = result.current,
            size = result.size,
        )
    }

    /**
     * Deletes a file row.
     *
     * @param id file ID
     * @return true=deleted; false=not found
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun delete(id: String): Boolean = session {
        // A wrapper delete, not deleteById: the latter resolves the entity type from the
        // mapper proxy at call time, which is an interface MyBatis-Plus cannot introspect
        // without Spring
        delete(KtQueryWrapper(FileboxFileEntity::class.java).eq(FileboxFileEntity::id, id)) > 0
    }

    private inline fun <T> session(block: FileboxFileMapper.() -> T): T =
        sessions.openSession(true).use { sqlSession ->
            block(sqlSession.getMapper(FileboxFileMapper::class.java))
        }
}
