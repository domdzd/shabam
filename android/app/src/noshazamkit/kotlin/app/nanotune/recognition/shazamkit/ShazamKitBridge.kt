package app.nanotune.recognition.shazamkit

import app.nanotune.recognition.StreamingRecognitionProvider

/**
 * Compiled when Apple's ShazamKit AAR was not present at build time (see app/libs/README.md).
 * The app then honestly reports that the recognition engine is not included in this build.
 */
object ShazamKitBridge {
    const val INCLUDED: Boolean = false
    const val SDK_DESCRIPTION: String = "Not included in this build"

    @Suppress("UNUSED_PARAMETER")
    fun createProvider(tokenSource: ShazamTokenSource): StreamingRecognitionProvider? = null
}
