package com.example.filebox

/**
 * Filebox business error
 *
 * Carries the code the API answers with, so the controller stays a translation layer
 * between HTTP and these calls.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
class FileboxException(val code: Int, message: String) : RuntimeException(message)
