package com.example.filebox.db

import com.baomidou.mybatisplus.annotation.DbType
import com.baomidou.mybatisplus.core.MybatisConfiguration
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder
import com.baomidou.mybatisplus.core.toolkit.reflect.GenericTypeUtils
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor
import com.example.filebox.db.mapper.FileboxFileMapper
import com.example.filebox.db.mapper.FileboxSnapshotMapper
import com.example.filebox.db.repository.FileboxFileRepository
import com.example.filebox.db.repository.FileboxSnapshotRepository
import org.apache.ibatis.mapping.Environment
import org.apache.ibatis.session.SqlSessionFactory
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * One migrated plugin datasource and its repositories.
 *
 * The schema is applied by Flyway before any session is opened, so a failed migration fails the
 * mount and lands in the plugin's `loadError` instead of surfacing on the first request.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class FileboxDatabase private constructor(sessions: SqlSessionFactory) {
    /**
     * File rows
     */
    val files = FileboxFileRepository(sessions)

    /**
     * Snapshot rows
     */
    val snapshots = FileboxSnapshotRepository(sessions)

    companion object {
        /**
         * Flyway history table, namespaced so several plugins may share a database
         */
        const val HISTORY_TABLE = "t_p_filebox_schema_history"

        /**
         * Migrates a datasource and builds its session factory.
         *
         * @param dataSource plugin datasource
         * @param dbType dialect of the datasource
         * @param location classpath location of the migration scripts
         * @return migrated database
         * @author FatttSnake, fatttsnake@gmail.com
         * @since 1.0.0
         */
        fun open(dataSource: DataSource, dbType: DbType, location: String): FileboxDatabase {
            migrate(dataSource, location)

            return FileboxDatabase(sessionFactory(dataSource, dbType))
        }

        private fun migrate(dataSource: DataSource, location: String) {
            Flyway
                .configure(FileboxDatabase::class.java.classLoader)
                .dataSource(dataSource)
                .locations(location)
                .baselineOnMigrate(true)
                .table(HISTORY_TABLE)
                .baselineVersion("0")
                .load()
                .migrate()
        }

        private fun sessionFactory(dataSource: DataSource, dbType: DbType): SqlSessionFactory {
            // MyBatis-Plus would otherwise reach for Spring's resolver, which the plugin class
            // loader does not expose
            GenericTypeUtils.setGenericTypeResolver(MybatisTypeResolver)

            val pagination =
                MybatisPlusInterceptor().apply {
                    addInnerInterceptor(PaginationInnerInterceptor(dbType))
                }
            val configuration =
                MybatisConfiguration().apply {
                    environment = Environment("filebox", JdbcTransactionFactory(), dataSource)
                    addInterceptor(pagination)
                    addMapper(FileboxFileMapper::class.java)
                    addMapper(FileboxSnapshotMapper::class.java)
                }

            return MybatisSqlSessionFactoryBuilder().build(configuration)
        }
    }
}
