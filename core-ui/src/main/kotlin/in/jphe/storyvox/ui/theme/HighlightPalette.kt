package `in`.jphe.storyvox.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Issue #999 phase 2 — the small swatch palette the in-reader highlight
 * overlay offers when creating a highlight.
 *
 * Persisted by *name* (the enum constant string) in [Annotation.color], never
 * by ordinal — the entity's documented string-not-ordinal contract, so a new
 * swatch can be appended here without a schema migration and an unknown stored
 * name degrades gracefully via [fromName] rather than crashing on an
 * out-of-range ordinal.
 *
 * The swatches are warm brass/parchment tones drawn from the same family as
 * the reading themes (#993) so a highlight sits in the realm's visual language
 * rather than the garish yellow/green/pink of a generic highlighter. Each is
 * applied as a translucent *background* span behind the body text (the dark
 * ink reads through the wash), exactly like #998's search-hit fill and #994's
 * word fill — an orthogonal layer over the spoken-sentence underline.
 */
enum class HighlightColor(
    /** The opaque swatch shown in the colour picker chips. */
    val swatch: Color,
) {
    /** Warm brass — the default, harmonises with the sentence underline. */
    Brass(Color(0xFFC9A774)),

    /** Soft amber/gold — a brighter warm tone for emphasis. */
    Amber(Color(0xFFE0B84A)),

    /** Sage green — a cool counterpoint that still reads as parchment-era. */
    Sage(Color(0xFF8FA86B)),

    /** Dusty rose — a muted warm pink for a third distinguishable hue. */
    Rose(Color(0xFFC98B8B)),

    /** Slate blue — a calm cool tone for a fourth category. */
    Slate(Color(0xFF7E92B5)),
    ;

    /**
     * The translucent fill painted behind the body text. 0.30 alpha is a soft
     * wash the dark ink reads through cleanly — a touch stronger than the
     * 0.25 sentence/word fill so a *saved* highlight is distinguishable from
     * the transient spoken-sentence wash without overwhelming the glyphs.
     */
    val fill: Color get() = swatch.copy(alpha = 0.30f)

    companion object {
        /** The swatch a new highlight gets if the user doesn't pick one. */
        val Default: HighlightColor = Brass

        /**
         * Resolve a persisted [Annotation.color] name back to a swatch.
         * Unknown / legacy names (a future swatch removed, a hand-edited row)
         * fall back to [Default] rather than throwing — the highlight still
         * renders, just in the default tone.
         */
        fun fromName(name: String?): HighlightColor =
            entries.firstOrNull { it.name == name } ?: Default
    }
}
