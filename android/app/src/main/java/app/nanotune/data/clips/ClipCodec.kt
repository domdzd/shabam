package app.nanotune.data.clips

/**
 * Turns a recording's WAV bytes into the bytes stored on disk and back. Kept as a tiny interface
 * so [ClipStore] stays plain JVM code (unit-tested without a device); the app plugs in an Android
 * Keystore implementation that encrypts recordings at rest.
 */
interface ClipCodec {
    /** File extension of audio written with this codec, including the dot. */
    val extension: String

    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray

    /** Stores WAV files as they are (used in tests and as the fallback if encryption fails). */
    object Plain : ClipCodec {
        override val extension: String = ".wav"

        override fun seal(plain: ByteArray): ByteArray = plain

        override fun open(sealed: ByteArray): ByteArray = sealed
    }
}
