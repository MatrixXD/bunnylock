package dev.ririxi.bunnylock.data

import org.json.JSONObject

/** The helper couldn't be started or reached, or it refused a command. Despite the name, the Shizuku client throws it too. */
class RootException(
    message: String,
) : Exception(message)

interface PrivilegedClient {
    val running: Boolean

    /** Starts the helper and returns its hello: uid, version and whether the QRTR library loaded. */
    suspend fun start(): JSONObject

    /**
     * Sends [cmd] with the fields that [build] adds to the request and returns the "data" object of the helper's reply.
     * Throws [RootException] when the helper reports an error or doesn't answer within [timeoutMs].
     */
    suspend fun call(
        cmd: String,
        timeoutMs: Long = 20_000,
        build: JSONObject.() -> Unit = {},
    ): JSONObject

    fun stop()
}
