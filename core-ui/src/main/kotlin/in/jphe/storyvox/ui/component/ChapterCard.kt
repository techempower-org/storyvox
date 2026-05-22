package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.playback.cache.ChapterCacheState
import `in`.jphe.storyvox.ui.a11y.A11ySpeakChapterMode
import `in`.jphe.storyvox.ui.a11y.LocalA11ySpeakChapterMode
import `in`.jphe.storyvox.ui.a11y.LocalAccessibleTouchTargets
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Immutable
data class ChapterCardState(
    val number: Int,
    val title: String,
    val publishedRelative: String,
    val durationLabel: String,
    val isDownloaded: Boolean,
    val isFinished: Boolean,
    val isCurrent: Boolean,
    /** PR-H (#86) — PCM cache state for this chapter under the user's
     *  currently-active voice. Defaults to [ChapterCacheState.None] for
     *  back-compat with call sites that haven't yet computed cache state
     *  (previews, tests, the Library card path which doesn't combine in
     *  the per-chapter inspector flow yet — that's a PR-H follow-up).
     *  `FictionDetailScreen.toCardState` forwards the real value from
     *  the view-model's per-fiction cache-state flow. */
    val cacheState: ChapterCacheState = ChapterCacheState.None,
)

@Composable
fun ChapterCard(
    state: ChapterCardState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val highlight = if (state.isCurrent)
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

    // #486 Phase 2 — TalkBack chapter-header readout branching. The
    // sighted-listener content description always includes both
    // (numbers help the listener orient: "I'm on chapter 6 of 38").
    // TalkBack users have a per-app preference for one of:
    //  - Both: "Chapter 6, The Brass Sigil, 28 minutes"
    //  - NumbersOnly: "Chapter 6, 28 minutes"
    //  - TitlesOnly: "The Brass Sigil, 28 minutes"
    // The pref is shared across screens; here we read it once and the
    // chapter-row semantics block branches the description.
    val speakMode = LocalA11ySpeakChapterMode.current
    val chapterDescription = when (speakMode) {
        A11ySpeakChapterMode.Both ->
            "Chapter ${state.number}, ${state.title}, ${state.durationLabel}"
        A11ySpeakChapterMode.NumbersOnly ->
            "Chapter ${state.number}, ${state.durationLabel}"
        A11ySpeakChapterMode.TitlesOnly ->
            "${state.title}, ${state.durationLabel}"
    } +
        // PR-H (#86) — cache-state segment of the chapter row's TalkBack
        // readout. Order matches the visual layout: cache state first
        // (closest to the title column), then downloaded. Skipped for
        // None so a chapter with no cache + no download reads as the
        // pre-PR-H "Chapter N, Title, M min." with no trailing clauses.
        when (state.cacheState) {
            ChapterCacheState.Complete -> ", cached, plays instantly"
            ChapterCacheState.Partial -> ", caching in progress"
            ChapterCacheState.None -> ""
        } +
        if (state.isDownloaded) ", downloaded" else ""

    // Issue #461 — the previous fix (#295) used `Card(onClick = ...)` plus
    // a sibling `Modifier.semantics(mergeDescendants = true)` on the outer
    // surface. That works in isolation but regressed in Compose Material3
    // 1.2+: the Card's internal clickable lives on a child of the outer
    // semantics node, and uiautomator/Espresso again report
    // `clickable=false` on the row because the parent semantics node has
    // no action node attached. Pull the click handler onto the SAME outer
    // modifier as the semantics block so both action + role + contentDesc
    // live on a single a11y node — the row is now reliably a Button with
    // an onClick. Use `Card { … }` (the non-clickable overload) for the
    // visual chrome only.
    //
    // Issue #612 (v1.0 blocker) — pre-fix the outer Card carried
    // `.semantics(mergeDescendants = true) { contentDescription = ... }`
    // which announced the computed chapterDescription AND merged in
    // the children Texts (chapter number, stripped title, published,
    // duration). TalkBack on R5CRB0W66MK then read the row twice:
    // once via the explicit description ("Chapter 6, The Brass Sigil,
    // 28 minutes") and again via the merged child labels (the index
    // numeral "06" then the title, etc.). Swap mergeDescendants for
    // `clearAndSetSemantics` so the children's text nodes are
    // suppressed and only the curated description is announced. The
    // clickable still owns the action — `clearAndSetSemantics` doesn't
    // strip merged actions from the SAME modifier chain (only from
    // descendants), so the row stays tappable.
    // #690 Phase 2 — under [LocalAccessibleTouchTargets], floor the
    // chapter row to 80dp so motor-impaired / Switch Access users have
    // a comfortably wide tap surface for chapter selection. The
    // chapter list is the deepest scrollable list in the reader flow,
    // so the row growth is load-bearing for those users.
    val enlarged = LocalAccessibleTouchTargets.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { m -> if (enlarged) m.heightIn(min = 80.dp) else m }
            .clickable(
                role = Role.Button,
                onClickLabel = "Open chapter",
                onClick = onClick,
            )
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = chapterDescription
                onClick(label = "Open chapter") { onClick(); true }
            },
        colors = highlight,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = state.number.toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Issue #256 — the left-side brass index already shows the
                    // chapter number (e.g. "06"), so titles that start with
                    // their own "Ch. N -" / "Chapter N:" prefix produced two
                    // numbers racing on the same row: brass '06' + 'CH. 116
                    // - Insurmountable Foe'. Strip the redundant prefix so
                    // the visible title is the actual title; the brass
                    // numeral remains the canonical chapter number.
                    stripChapterPrefix(state.title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(state.publishedRelative, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.durationLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // PR-H (#86) — PCM cache-state badge. Sits BEFORE the
            // existing downloaded/finished icons so the visual sweep is
            // cache → downloaded → finished, mirroring the listening
            // workflow (cache it, mark it offline, finish it). The
            // composable is invisible for [ChapterCacheState.None]
            // (zero rendered footprint, no spacing consumed by the
            // parent Row.spacedBy) so the layout matches the pre-PR-H
            // card for the common-case empty-cache chapter.
            ChapterCacheBadge(state = state.cacheState)
            if (state.isDownloaded) {
                Icon(
                    imageVector = Icons.Filled.OfflineBolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Icon(
                imageVector = if (state.isFinished) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (state.isFinished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Issue #461 — structural marker for [ChapterCardClickableTest]. Set to
 * `true` when the outer surface of [ChapterCard] uses an explicit
 * `Modifier.clickable` so the click action lives on the same node as the
 * `semantics { role; contentDescription }` block. The regressed
 * implementation (pre-#461) used `Card(onClick = …)` alone, with the
 * Card's internal clickable on a child node; uiautomator dumped the row
 * as `clickable=false`. If a future refactor returns to that shape,
 * flip this back to `false` AND remove the marker contract — but only
 * after re-verifying on a real device that rows are tappable.
 */
internal const val chapterCardUsesOuterClickable: Boolean = true

/**
 * Issue #256 — strip a redundant chapter-number prefix from a chapter
 * title. RR titles routinely look like:
 *   "Ch. 116 - Insurmountable Foe"
 *   "Chapter 7: The Brass Sigil"
 *   "Ch.7 - Greed"
 *   "CH. 0 - "
 * which collide with the brass left-side index. The regex covers the
 * common shapes; if no prefix matches the title is returned untouched
 * so non-conforming sources (mdbook chapters, RSS items) keep rendering
 * as-is. The trailing colon strip in #265 lives at a different layer
 * (source side) but the same intent — collapse double signals into one.
 */
internal fun stripChapterPrefix(title: String): String {
    // Matches: optional `Ch`/`Chapter` (case-insensitive), optional `.`,
    // whitespace, integer, optional whitespace + dash/colon/em-dash,
    // followed by optional whitespace.
    val regex = Regex("^(?:ch(?:apter)?\\.?)\\s*\\d+\\s*[-:—–]\\s*", RegexOption.IGNORE_CASE)
    val stripped = regex.replaceFirst(title, "").trim()
    // Don't return an empty string — that'd render as a blank row.
    // Fall back to the original if stripping leaves nothing readable.
    return stripped.ifEmpty { title }
}
