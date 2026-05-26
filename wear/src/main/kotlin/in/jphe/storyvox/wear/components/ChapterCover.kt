package `in`.jphe.storyvox.wear.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import coil3.compose.AsyncImage
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.wear.theme.BrassMuted
import `in`.jphe.storyvox.wear.theme.BrassPrimary

/**
 * Chapter cover artwork — renders inside the circular scrubber.
 *
 * If [coverUri] is non-null we hand it to Coil's [AsyncImage]; otherwise we
 * render the brass-on-warm-dark monogram (same `fictionMonogram` helper the
 * phone/tablet uses — see issue #322 for the rationale on `✦` vs `?` as the
 * final-fallback glyph).
 *
 * The cover is clipped to a circle so it nests cleanly inside the brass ring;
 * on square Wear faces (rare) the call site can pass a rounded-rect modifier
 * to override the clip.
 */
@Composable
fun ChapterCover(
    coverUri: String?,
    title: String?,
    author: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(BrassMuted),
        contentAlignment = Alignment.Center,
    ) {
        if (!coverUri.isNullOrBlank()) {
            AsyncImage(
                model = coverUri,
                contentDescription = title?.let { "Cover for $it" } ?: "Chapter cover",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = fictionMonogram(
                    author = author.orEmpty(),
                    title = title.orEmpty(),
                ),
                color = BrassPrimary,
                fontSize = 36.sp,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.display1,
            )
        }
    }
}
