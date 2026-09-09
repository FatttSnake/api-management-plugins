package com.example.echo

import org.springframework.stereotype.Service

/**
 * Echo service
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
@Service
class EchoService {
    /**
     * Echo message
     *
     * @return Message
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    fun message(): String = "pong"
}
