package com.example.filebox.model

/**
 * One page of query results.
 *
 * @property records records of this page
 * @property total number of records matching the query
 * @property page current page number, starting at 1
 * @property size page size
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
data class FileboxPage<T>(
    val records: List<T>,
    val total: Long,
    val page: Long,
    val size: Long,
)
