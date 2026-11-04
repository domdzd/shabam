package app.nanotune.recognition.shazamkit

/** Rules for reusing a cached ShazamKit developer token. */
object TokenPolicy {
    /** Refresh this long before the real expiry so an in-flight match never uses a dead token. */
    const val REFRESH_MARGIN_SECONDS: Long = 5 * 60

    fun isUsable(expiresAtEpochSeconds: Long, nowEpochSeconds: Long, marginSeconds: Long = REFRESH_MARGIN_SECONDS): Boolean =
        expiresAtEpochSeconds - nowEpochSeconds > marginSeconds

    /**
     * Expiry to store locally, measured on the device clock from the server's relative
     * `expires_in`. Absolute server timestamps are not compared with the device clock because
     * phones are often minutes or hours off.
     */
    fun localExpiry(expiresInSeconds: Long, nowEpochSeconds: Long): Long =
        nowEpochSeconds + expiresInSeconds.coerceAtLeast(0)
}
