package com.example.filebox.db.entity

import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.example.filebox.model.FileboxSnapshot

/**
 * Row of `t_p_filebox_snapshot`.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
@TableName("t_p_filebox_snapshot")
data class FileboxSnapshotEntity(
    @field:TableId(value = "content_key", type = IdType.INPUT)
    var contentKey: String = "",

    @field:TableField("owner_id")
    var ownerId: Long = 0,

    @field:TableField("size")
    var size: Long = 0,

    @field:TableField("create_time")
    var createTime: Long = 0,
)

/**
 * Maps the row to the domain model.
 *
 * @return snapshot metadata
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
fun FileboxSnapshotEntity.toModel(): FileboxSnapshot = FileboxSnapshot(
    contentKey = contentKey,
    ownerId = ownerId,
    size = size,
    createTime = createTime,
)

/**
 * Maps the domain model to its row.
 *
 * @return snapshot row
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
fun FileboxSnapshot.toEntity(): FileboxSnapshotEntity = FileboxSnapshotEntity(
    contentKey = contentKey,
    ownerId = ownerId,
    size = size,
    createTime = createTime,
)
