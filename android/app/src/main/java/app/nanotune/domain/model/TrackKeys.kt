package app.nanotune.domain.model

import java.text.Normalizer
import java.util.Locale

/**
 * Stable keys for tracks.
 *
 * * [identityKey] identifies a stored track (and is synchronised between devices). It prefers the
 *   provider ID, so a live version and a studio version stay separate entries.
 * * [similarityKey] answers "is this the same song?" for duplicate prevention. It only uses the
 *   normalised artist and title, so it also catches the same song reported with two IDs.
 */
object TrackKeys {
    fun identityKey(track: RecognizedTrack): String =
        track.providerTrackId?.trim()?.takeIf { it.isNotEmpty() }?.let { "id:$it" }
            ?: similarityKey(track.artist, track.title)

    fun similarityKey(track: RecognizedTrack): String = similarityKey(track.artist, track.title)

    fun similarityKey(artist: String, title: String): String = "meta:${normalize(artist)}|${normalize(title)}"

    private val combiningMarks = Regex("\\p{Mn}+")
    private val nonAlphanumeric = Regex("[^\\p{L}\\p{Nd}]+")

    /** Lower-cases, strips accents and punctuation, and collapses whitespace. */
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
        return combiningMarks.replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .replace(nonAlphanumeric, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
