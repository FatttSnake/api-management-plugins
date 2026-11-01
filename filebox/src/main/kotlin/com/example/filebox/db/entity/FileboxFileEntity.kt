package com.example.filebox.db.entity

import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.example.filebox.model.FileboxFile

/**
 * Row of `t_p_filebox_file`.
 *
 * Mutable with defaults so MyBatis can instantiate it through its no-argument constructor and
 * fill it through setters.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
@TableName("t_p_filebox_file")
data class FileboxFileEntity(
    @field:TableId(type = IdType.INPUT)
    var id: String = "",

    @field:TableField("owner_id")
    var ownerId: Long = 0,

    @field:TableField("name")
    var name: String = "",

    @field:TableField("size")
    var size: Long = 0,

    @field:TableField("path")
    var path: String = "",

    @field:TableField("create_time")
    var createTime: Long = 0,
)

/**
 * Maps the row to the domain model.
 *
 * @return file metadata
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
fun FileboxFileEntity.toModel(): FileboxFile = FileboxFile(
    id = id,
    ownerId = ownerId,
    name = name,
    size = size,
    path = path,
    createTime = createTime,
)

/**
 * Maps the domain model to its row.
 *
 * @return file row
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
fun FileboxFile.toEntity(): FileboxFileEntity = FileboxFileEntity(
    id = id,
    ownerId = ownerId,
    name = name,
    size = size,
    path = path,
    createTime = createTime,
)
