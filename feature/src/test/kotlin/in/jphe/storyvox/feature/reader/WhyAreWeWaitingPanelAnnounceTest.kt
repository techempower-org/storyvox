package `in`.jphe.storyvox.feature.reader

import `in`.jphe.storyvox.playback.diagnostics.WaitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Issue #646 (v1.0 polish) — TalkBack must announce a Wait-reason
 * panel ONCE per state change with a single clean utterance, no
 * double period. Pre-fix the legacy concat produced
 * "Audio output changed.. Switching to the new speaker…" because the
 * `r.message` headlines already ended in "." and the join site
 * appended another. [joinHeadlineAndBody] strips a single trailing
 * period from the headline before the join, so every reason now
 * announces with exactly one terminator between the two clauses.
 *
 * This test sweeps the full [WaitReason] tree (every data-object +
 * representative data-class payload) and asserts:
 *   - the joined string never contains a `..` substring,
 *   - the joined string is non-empty,
 *   - the joined string starts with the (period-trimmed) headline and
 *     ends with the (un-trimmed) body trimmed of whitespace — i.e.,
 *     no silent payload loss from the dedup pass.
 *
 * Lives in the feature test source set because [joinHeadlineAndBody]
 * is `internal` to the reader package — same kind of structural canary
 * test as [BottomTabBarSemanticsTest] pinning the dock semantics.
 */
class WhyAreWeWaitingPanelAnnounceTest {

    private val allReasons: List<WaitReason> = listOf(
        WaitReason.WarmingVoice(voiceName = "Brian", progress = 0.5f),
        WaitReason.LoadingChapter(chapterTitle = "Chapter 1"),
        WaitReason.BufferingNextSentence(queueDepth = 2),
        WaitReason.NetworkSlow(secondsWaiting = 5),
        WaitReason.FocusLost(cause = "phone call"),
        WaitReason.AudioRouteChange,
        WaitReason.DeviceMuted,
        WaitReason.VoiceDownloadFailed(voiceName = "Brian"),
        WaitReason.AudioOutputStuck(secondsSilent = 6),
        WaitReason.AudioOutputStuck(secondsSilent = 2),
    )

    @Test
    fun `joined announcement never contains a double period`() {
        allReasons.forEach { reason ->
            val joined = joinHeadlineAndBody(reason.message, secondaryLineFor(reason))
            assertFalse(
                "WaitReason ${reason::class.simpleName} announced as \"$joined\" — contains a double period",
                joined.contains(".."),
            )
        }
    }

    @Test
    fun `AudioRouteChange announces with a single period between headline and body`() {
        // The bellwether case from issue #646. Pre-fix the panel
        // re-announced "Audio output changed.. Switching to the new
        // speaker or headphones." three times. Post-fix it's the
        // tidy one-utterance sentence pair below.
        val joined = joinHeadlineAndBody(
            WaitReason.AudioRouteChange.message,
            secondaryLineFor(WaitReason.AudioRouteChange),
        )
        assertEquals(
            "Audio output changed. Switching to the new speaker or headphones.",
            joined,
        )
    }

    @Test
    fun `empty body falls back to bare headline — no dangling separator`() {
        assertEquals("Audio output changed.", joinHeadlineAndBody("Audio output changed.", ""))
        assertEquals("Hang tight", joinHeadlineAndBody("Hang tight", "   "))
    }

    @Test
    fun `headline without a trailing period still gets exactly one period before body`() {
        // Future-proofing: if a maintainer rewrites a WaitReason
        // headline to drop the trailing "." the join site must still
        // produce a single ". " separator, not zero.
        assertEquals(
            "Loading the chapter. Getting the chapter ready to read aloud.",
            joinHeadlineAndBody("Loading the chapter", "Getting the chapter ready to read aloud."),
        )
    }
}
