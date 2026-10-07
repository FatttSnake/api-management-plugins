package com.example.filebox

/**
 * Filebox business code
 *
 * Laid out the way the SDK suggests: a client error is 1xxx, a business failure 2xxx and
 * a server-side one 5xxx.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
object FileboxCode {
    /**
     * A caller-supplied argument is unusable
     */
    const val INVALID_ARGUMENT = 1000

    /**
     * The call is not tied to a user
     */
    const val UNAUTHENTICATED = 1001

    /**
     * No such file
     */
    const val FILE_NOT_FOUND = 2001

    /**
     * The file belongs to somebody else
     */
    const val ACCESS_DENIED = 2002

    /**
     * Text snapshots are switched off by configuration
     */
    const val SNAPSHOT_DISABLED = 2003

    /**
     * The configured upload token does not match
     */
    const val UPLOAD_TOKEN_MISMATCH = 2004

    /**
     * The file exceeds the configured size limit
     */
    const val FILE_TOO_LARGE = 2005

    /**
     * No such snapshot
     */
    const val SNAPSHOT_NOT_FOUND = 2006

    /**
     * The plugin has no datasource configured
     */
    const val DATASOURCE_NOT_CONFIGURED = 5001

    /**
     * The gateway cannot build an external link
     */
    const val EXTERNAL_URL_UNAVAILABLE = 5002
}
