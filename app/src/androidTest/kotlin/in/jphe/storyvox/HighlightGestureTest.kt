package `in`.jphe.storyvox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.reader.ReaderTextView
import `in`.jphe.storyvox.ui.component.TestTags
import `in`.jphe.storyvox.ui.theme.HighlightColor
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference

/**
 * Layer 3 ship gate for #1088 (the #1079 in-reader highlight). This is the
 * test JP asked for: prove the **select-text → highlight gesture** works
 * deterministically *on a real device*, which Maestro/adb cannot do (neither
 * can synthesize a text-selection long-press-drag against laid-out glyphs).
 *
 * ## What it proves
 * The full create-highlight wiring, end to end, on-device:
 *
 *   long-press the body  →  [ReaderTextView] resolves the press to a chapter
 *   char-offset via `TextLayoutResult.getOffsetForPosition` + `getWordBoundary`
 *   (the production path in `SentenceHighlight`'s
 *   `detectDragGesturesAfterLongPress`)  →  the drag-finished callback sets
 *   `pendingSelection`  →  [TestTags.HighlightSheet] (the create
 *   [HighlightCreateSheet]) animates in  →  pick a swatch in
 *   [TestTags.HighlightPalette]  →  tap [TestTags.HighlightConfirm]  →  the
 *   composable invokes `onCreateHighlight(start, end, quotedText, color, note)`
 *   with a normalised, in-bounds half-open range.
 *
 * ## Why render [ReaderTextView] standalone (not launch MainActivity)
 * [ReaderTextView] is a pure, parameter-driven composable — it takes the
 * chapter text + hoisted highlight callbacks with no ViewModel, Hilt, DB, or
 * navigation. Rendering it directly with [createComposeRule] (which still runs
 * on the device, with the real Compose runtime + real text layout, so
 * `getOffsetForPosition` and the long-press detector behave exactly as in
 * production) gives a hermetic, deterministic test: known `chapterText` in, a
 * captured `onCreateHighlight` out. It sidesteps the broken LOAD_SAMPLE seed
 * and the MainActivity launch obstacles (singleTask + POST_NOTIFICATIONS) that
 * [LaunchSmokeTest] had to work around — none of them are relevant to the
 * gesture under test.
 *
 * ## Why a long-press (not a multi-point drag)
 * The gesture is `detectDragGesturesAfterLongPress`: `onDragStart` already
 * resolves the press point to a **whole word** (anchor = word.start, focus =
 * word.end) and fires the selection callback, and lifting fires
 * `onSelectionFinished` → the sheet shows. A bare [longClick] therefore selects
 * exactly one word deterministically — no fragile pixel arithmetic across a
 * multi-line body and across device densities. That keeps the gesture *real*
 * (it drives the production touch → offset → word-boundary path) while staying
 * reproducible, which is exactly the determinism team-lead asked for. The
 * separate [normalizeSelection] / [annotationIdAtOffset] offset math is already
 * covered by JVM unit tests (ReaderHighlightSelectionTest, 13/13); this test
 * owns the on-device gesture → sheet → create wiring those unit tests can't see.
 */
@RunWith(AndroidJUnit4::class)
class HighlightGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Deterministic body text. Long enough to fill the viewport so the
     * `reader-body` node's *centre* (performTouchInput's default press point)
     * lands squarely on a laid-out body line rather than the empty tail of a
     * short paragraph — that empty-tail miss is what timed out the first run.
     * Repeated sentences keep it word-dense so any centre press resolves to a
     * whole word; the exact word doesn't matter, only that the selection is a
     * non-empty in-bounds range.
     */
    private val sampleChapter: String = buildString {
        repeat(40) {
            append(
                "The lantern guttered as Selene crossed the silent archive hall, " +
                    "her footsteps swallowed by the dust of a thousand forgotten tales. ",
            )
        }
    }.trim()

    /** A stopped, minimal playback state — nothing is playing, no sentence is
     *  highlighted, so the only live behaviour is the selection gesture. */
    private fun stoppedState() = UiPlaybackState(
        fictionId = "test-fiction",
        chapterId = "test-chapter",
        chapterTitle = "Chapter One",
        fictionTitle = "The Silent Archive",
        coverUrl = null,
        isPlaying = false,
        positionMs = 0L,
        durationMs = 0L,
        sentenceStart = 0,
        sentenceEnd = 0,
        speed = 1.0f,
        pitch = 1.0f,
        voiceId = null,
        voiceLabel = "",
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun longPressSelect_thenConfirm_createsHighlightWithSaneRange() {
        // Capture what onCreateHighlight is called with — the contract this
        // gate verifies. AtomicReference because the callback fires on the
        // Compose (UI) thread and we assert on the test thread.
        val created = AtomicReference<CreatedHighlight?>(null)

        composeRule.setContent {
            LibraryNocturneTheme {
                ReaderTextView(
                    state = stoppedState(),
                    chapterText = sampleChapter,
                    onPlayPause = {},
                    onCreateHighlight = { start, end, quoted, color, note ->
                        created.set(CreatedHighlight(start, end, quoted, color, note))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 1) Drive the real select gesture: long-press the laid-out body text
        //    at the reader-body node's centre. The body text fills the viewport
        //    (see sampleChapter), so the centre lands on a real glyph line —
        //    SentenceHighlight's detectDragGesturesAfterLongPress resolves the
        //    press to a whole-word char range (via getOffsetForPosition +
        //    getWordBoundary) and fires onSelectionFinished on lift, which sets
        //    pendingSelection and raises the create sheet. A bare long-press is
        //    sufficient: this detector calls onDragStart on long-press
        //    completion (before any movement), so no fragile multi-point drag
        //    arithmetic is needed.
        composeRule.onNodeWithTag(TestTags.ReaderBody)
            .performTouchInput { longClick() }

        // 2) The create sheet is a ModalBottomSheet — it animates in, so wait
        //    for its tagged container before interacting.
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithTag(TestTags.HighlightSheet)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TestTags.HighlightSheet).assertExists()

        // 3) Pick a non-default swatch (index 2 == Sage) from the palette, so
        //    the asserted colour proves the palette tap propagated rather than
        //    silently passing through the Brass default.
        composeRule.onNodeWithTag(TestTags.HighlightPalette)
            .onChildAt(SAGE_SWATCH_INDEX)
            .performClick()

        // 4) Confirm — fires onCreateHighlight with the normalised range.
        composeRule.onNodeWithTag(TestTags.HighlightConfirm).performClick()
        composeRule.waitForIdle()

        // 5) Assert the create contract: a highlight was created over a sane,
        //    in-bounds, non-empty half-open range, with the swatch we tapped.
        val h = created.get()
        assertNotNull("onCreateHighlight should have fired after confirm", h)
        requireNotNull(h)
        assertTrue("start must be >= 0 (was ${h.start})", h.start >= 0)
        assertTrue("end must be > start (was ${h.start}..${h.end})", h.end > h.start)
        assertTrue(
            "end must be within the chapter body (end=${h.end}, len=${sampleChapter.length})",
            h.end <= sampleChapter.length,
        )
        assertEquals(
            "quotedText must be the substring the offsets denote",
            sampleChapter.substring(h.start, h.end),
            h.quotedText,
        )
        assertTrue("quotedText must be non-empty", h.quotedText.isNotEmpty())
        assertEquals(
            "the tapped swatch should be the persisted colour name",
            HighlightColor.Sage.name,
            h.colorName,
        )
    }

    private data class CreatedHighlight(
        val start: Int,
        val end: Int,
        val quotedText: String,
        val colorName: String,
        val note: String?,
    )

    private companion object {
        // HighlightColor.entries order: Brass(0), Amber(1), Sage(2), Rose(3),
        // Slate(4). Tapping index 2 selects Sage — a deterministic non-default.
        const val SAGE_SWATCH_INDEX = 2
    }
}
