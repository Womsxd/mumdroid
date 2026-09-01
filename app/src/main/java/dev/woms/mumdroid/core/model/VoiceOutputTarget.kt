package dev.woms.mumdroid.core.model

/**
 * Logical playback destinations the user can rank and switch between.
 *
 * Wired / USB headphones, a Bluetooth headset and the loudspeaker can each
 * use the communication or media path. The earpiece is communication-only.
 */
enum class VoiceOutputTarget {
    HEADSET,
    BLUETOOTH,
    SPEAKER,
    EARPIECE,
    ;

    companion object {
        /**
         * Default join order: a connected headset wins, then Bluetooth, then
         * the earpiece (matching the old speaker-off default). The loudspeaker
         * is last so a phone without accessories still uses the earpiece.
         */
        val DEFAULT_ORDER: List<VoiceOutputTarget> = listOf(
            HEADSET,
            BLUETOOTH,
            EARPIECE,
            SPEAKER,
        )

        /** Speaker-first order used when migrating the old loudspeaker toggle. */
        val SPEAKER_FIRST_ORDER: List<VoiceOutputTarget> = listOf(
            SPEAKER,
            HEADSET,
            BLUETOOTH,
            EARPIECE,
        )

        /** Deduplicates [order] and appends any missing targets. */
        fun normalize(order: List<VoiceOutputTarget>): List<VoiceOutputTarget> {
            val seen = LinkedHashSet<VoiceOutputTarget>()
            for (item in order) seen += item
            for (item in DEFAULT_ORDER) if (item !in seen) seen += item
            for (item in entries) if (item !in seen) seen += item
            return seen.toList()
        }

        /** Moves the item at [index] by [delta] positions (−1 up, +1 down). */
        fun move(order: List<VoiceOutputTarget>, index: Int, delta: Int): List<VoiceOutputTarget> {
            val list = normalize(order).toMutableList()
            if (index !in list.indices) return list
            val to = (index + delta).coerceIn(list.indices)
            if (to == index) return list
            val item = list.removeAt(index)
            list.add(to, item)
            return list
        }
    }
}
