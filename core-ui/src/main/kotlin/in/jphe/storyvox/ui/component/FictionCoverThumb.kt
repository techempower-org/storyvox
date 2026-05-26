package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import kotlin.math.min

/**
 * Async cover image with a multi-tier fallback cascade.
 *
 * v0.5.59 cascade (#cover-style-toggle):
 *
 *  1. **Remote cover URL** — `SubcomposeAsyncImage(coverUrl)` if
 *     [coverUrl] is non-null. Loaded with [contentScale] (default
 *     `Crop`) which biases focal-center, so Notion banner-aspect
 *     covers (~5:1) crop to the middle band of the image. Coil's
 *     disk cache + retry handles transient network failures; S3-signed
 *     URL expiry falls through to the chosen fallback below.
 *
 *  2. **User-selected fallback** — consults [`LocalCoverStyle`]:
 *      - [CoverStyleLocal.Monogram] (default since v0.5.59): the
 *        classic minimalist sigil tile + author initial.
 *        [chooseFallbackStyle] resolves this to the monogram tile.
 *      - [CoverStyleLocal.Branded]: the v0.5.51 [BrandedCoverTile] —
 *        warm gradient + EB-Garamond title + brass border. Only used
 *        when [title] is non-blank; blank titles still fall to the
 *        monogram tile (which doesn't need a title).
 *      - [CoverStyleLocal.CoverOnly]: a dim brass-ring outline
 *        placeholder ([DimCoverPlaceholderTile]). For users who'd
 *        rather see nothing salient than a stand-in.
 *
 *  3. **Loading slot** still uses the monogram tile rather than the
 *     branded cover so it's clearly differentiable from the terminal
 *     fallback — a sigil reads as "this fiction is bound to this
 *     mark", not "we gave up trying to load."
 *
 * Default flipped to Monogram in v0.5.59 (was Branded since v0.5.51).
 * Users opt back into Branded via Settings → Appearance → Book cover
 * style. See [`CoverStyleLocal`] for the enum.
 *
 * @param coverUrl Remote image URL; null skips straight to the
 *   user-selected fallback.
 * @param title Used for the branded tile's title and as the cover's
 *   content description.
 * @param monogram One- or two-letter mark used by the monogram sigil
 *   tile (default fallback + loading slot + Branded's empty-title
 *   fallback).
 * @param author Optional — rendered as a brass label on the branded
 *   tile. Skipped when null/blank.
 * @param sourceFamily Which watermark glyph the branded tile uses.
 *   Defaults to [CoverSourceFamily.Generic] for backwards-compatible
 *   call sites that don't yet know their source family.
 */
@Composable
fun FictionCoverThumb(
    coverUrl: String?,
    title: String,
    monogram: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    author: String? = null,
    sourceFamily: CoverSourceFamily = CoverSourceFamily.Generic,
) {
    val style = LocalCoverStyle.current
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(MaterialTheme.shapes.medium)
            .semantics { contentDescription = "Cover for $title" },
    ) {
        val fallback: @Composable () -> Unit = {
            FallbackTile(
                style = style,
                title = title,
                monogram = monogram,
                author = author,
                sourceFamily = sourceFamily,
            )
        }

        if (coverUrl.isNullOrBlank()) {
            // Skip the AsyncImage round-trip entirely when we already
            // know there's no URL — render the fallback straight away
            // rather than briefly showing the loading sigil and then
            // crossfading.
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                // Loading slot deliberately uses the monogram tile (not
                // the user-selected fallback) so loading reads as "in
                // progress" rather than "this is the cover". On a
                // CoverOnly user it would otherwise flash the dim
                // placeholder before the real image lands.
                loading = { MonogramSigilTile(monogram = monogram) },
                error = { fallback() },
            )
        }
    }
}

/**
 * Routes the active [CoverStyleLocal] to the concrete tile. Extracted
 * for testability — [chooseFallbackStyle] is the pure function the
 * cascade test pins; the composable here just dispatches on its
 * result.
 *
 * Empty-title rule: Branded needs a non-blank title to render
 * meaningfully (the title is the dominant visual element). Calls with
 * blank titles fall back to the monogram tile regardless of the
 * user's pref so we never render an awkward title-less BrandedCoverTile.
 */
@Composable
internal fun FallbackTile(
    style: CoverStyleLocal,
    title: String,
    monogram: String,
    author: String?,
    sourceFamily: CoverSourceFamily,
) {
    when (chooseFallbackStyle(style, title)) {
        ResolvedFallback.Branded -> BrandedCoverTile(
            title = title,
            author = author,
            sourceFamily = sourceFamily,
            modifier = Modifier.fillMaxSize(),
        )
        ResolvedFallback.Monogram -> MonogramSigilTile(monogram = monogram)
        ResolvedFallback.Dim -> DimCoverPlaceholderTile()
    }
}

/** Three concrete fallback tiles the cascade can render. */
internal enum class ResolvedFallback { Monogram, Branded, Dim }

/**
 * Pure-function cascade resolver. Pinned by `CoverStyleCascadeTest`.
 *
 * Rules:
 *  - [CoverStyleLocal.Monogram] → [ResolvedFallback.Monogram] always.
 *  - [CoverStyleLocal.Branded] → [ResolvedFallback.Branded] when the
 *    title is non-blank; otherwise [ResolvedFallback.Monogram] (the
 *    branded tile reads as broken with an empty title).
 *  - [CoverStyleLocal.CoverOnly] → [ResolvedFallback.Dim] always.
 *    The user has asked us not to fabricate cover-shaped content.
 */
internal fun chooseFallbackStyle(
    style: CoverStyleLocal,
    title: String,
): ResolvedFallback = when (style) {
    CoverStyleLocal.Monogram -> ResolvedFallback.Monogram
    CoverStyleLocal.Branded ->
        if (title.isNotBlank()) ResolvedFallback.Branded else ResolvedFallback.Monogram
    CoverStyleLocal.CoverOnly -> ResolvedFallback.Dim
}

/**
 * Static brass-sigil placeholder shown in place of a cover image when one
 * isn't available. Visual family deliberately matches [MagicSkeletonTile]
 * (the loading-state version) — same substrate, ring, six-pointed star,
 * brass palette — but holds still: a sigil reads as "this fiction is
 * bound to this mark," not "we're conjuring something."
 *
 * Monogram font-size scales with the container's smaller dimension via
 * [BoxWithConstraints] so the same component looks right in both the
 * 56-dp Follows thumbnail and the 220-dp audiobook player cover.
 *
 * Made `internal` in v0.5.59 so the Settings → Appearance preview
 * strip and the cascade-dispatch helper can share the same primitive.
 */
@Composable
internal fun MonogramSigilTile(monogram: String) {
    val brass = MaterialTheme.colorScheme.primary
    val brassDim = brass.copy(alpha = 0.40f)
    val brassFaint = brass.copy(alpha = 0.18f)
    val substrate = MaterialTheme.colorScheme.surfaceContainerHigh

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(substrate),
        contentAlignment = Alignment.Center,
    ) {
        // Aged-leather radial wash: lighter at the edges, slightly
        // darker at the center so the sigil reads against a faint
        // halo instead of a flat substrate.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            substrate.copy(alpha = 0f),
                            substrate.copy(alpha = 0.45f),
                        ),
                    ),
                ),
        )
        // Brass sigil — outer ring + six-pointed star, sized to ~70%
        // of the tile so the monogram can sit comfortably inside.
        Canvas(modifier = Modifier.fillMaxSize(0.72f)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val ringStroke = (radius * 0.05f).coerceAtLeast(1.5f)
            val starStroke = (radius * 0.07f).coerceAtLeast(2.0f)

            drawCircle(
                color = brassFaint,
                radius = radius * 0.95f,
                center = center,
                style = Stroke(width = ringStroke),
            )
            // Six-pointed star = two overlaid equilateral triangles
            drawTriangle(center, radius * 0.86f, 0f, brassDim, starStroke)
            drawTriangle(
                center,
                radius * 0.86f,
                60f,
                brassDim.copy(alpha = brassDim.alpha * 0.85f),
                starStroke,
            )
        }
        // Monogram letter inside the sigil — serif, brass, size scales
        // with the container so it looks right at 56 dp and 220 dp.
        val smallerDim = min(maxWidth.value, maxHeight.value)
        val monogramSize = (smallerDim * 0.32f).coerceIn(14f, 56f).sp
        Text(
            text = monogram,
            style = MaterialTheme.typography.displayMedium.copy(fontSize = monogramSize),
            color = brass,
        )
    }
}

/**
 * v0.5.59 — "Cover only" terminal placeholder. The user has asked
 * us not to fabricate cover-shaped content when no real cover is
 * available, so we render a deliberately understated dim brass-ring
 * outline on the warm-dark substrate. No title, no monogram, no
 * watermark — just enough to anchor the layout so the row doesn't
 * collapse.
 *
 * Kept visually quieter than [MonogramSigilTile] so users who turn
 * this on really do see "nothing salient" — the substrate is the same
 * surface as the rest of the row, the ring sits at 22% alpha. On a
 * Library hero row this reads as "we didn't have a cover for this".
 */
@Composable
internal fun DimCoverPlaceholderTile() {
    val brass = MaterialTheme.colorScheme.primary
    val ringColor = brass.copy(alpha = 0.22f)
    val substrate = MaterialTheme.colorScheme.surfaceContainerHigh
    // In @Preview / inspection, render the substrate flat — the
    // radial-gradient interaction with the preview's clipped canvas
    // can produce odd banding in Android Studio's preview pane.
    val inspectionMode = LocalInspectionMode.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(substrate),
        contentAlignment = Alignment.Center,
    ) {
        if (!inspectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                substrate.copy(alpha = 0f),
                                substrate.copy(alpha = 0.35f),
                            ),
                        ),
                    ),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize(0.55f)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val ringStroke = (radius * 0.06f).coerceAtLeast(1.5f)
            drawCircle(
                color = ringColor,
                radius = radius * 0.92f,
                center = center,
                style = Stroke(width = ringStroke),
            )
        }
    }
}

// drawTriangle is shared with MagicSkeletonTile via the internal helper
// in SkeletonShimmer.kt — same star geometry, same StrokeCap.Round, no
// duplicated drift between the loading and static placeholders.
