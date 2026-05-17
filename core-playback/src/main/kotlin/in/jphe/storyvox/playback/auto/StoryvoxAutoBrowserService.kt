package `in`.jphe.storyvox.playback.auto

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.MediaBrowserServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import `in`.jphe.storyvox.data.repository.FollowsRepository
import `in`.jphe.storyvox.data.repository.LibraryRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.playback.AutoBrowserConfig
import `in`.jphe.storyvox.playback.MediaSessionLocator
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Auto browse tree:
 *
 *   /              (root, browseable)
 *   ├── /resume    (playable — last book)
 *   ├── /library   (browseable, ≤6 children)
 *   ├── /follows   (browseable, ≤6 children)
 *   ├── /recent    (browseable, ≤6 children)
 *   └── /new       (browseable, ≤6 children)
 *
 * Auto's UX restrictions cap leaf depth and require artwork on every item.
 */
@AndroidEntryPoint
class StoryvoxAutoBrowserService : MediaBrowserServiceCompat() {

    @Inject lateinit var libraryRepo: LibraryRepository
    @Inject lateinit var followsRepo: FollowsRepository
    @Inject lateinit var positionRepo: PlaybackPositionRepository
    @Inject lateinit var sessionLocator: MediaSessionLocator
    /** Issue #598 — user-tunable bucket size. Read at every
     *  [onLoadChildren] call so a Settings flip takes effect the next
     *  time Auto refreshes the tree (typically when the user
     *  navigates back to a parent then forward into a category). */
    @Inject lateinit var autoConfig: AutoBrowserConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Defer session token attachment until the playback service has built it.
        // We can attach lazily when the first browser connects; if absent, we
        // return an empty list and the browser will retry.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot {
        val extras = Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1) // list
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2)  // grid
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        scope.launch {
            // Issue #598 — read the bucket size at the start of each
            // tree load so a Settings flip propagates without an Auto
            // service restart. Falls back to [MAX_PER_CATEGORY] (6,
            // the HMI guideline) on the very first call when the
            // store hasn't emitted yet.
            val bucket = autoConfig.currentItemsPerCategory()
            val items = when (parentId) {
                ROOT_ID -> rootItems()
                LIBRARY_ID -> libraryRepo.snapshot().take(bucket).map { it.toBrowsableItem() }
                FOLLOWS_ID -> followsRepo.snapshot().take(bucket).map { it.toBrowsableItem() }
                RECENT_ID -> positionRepo.recent(bucket).map { it.toPlayableItem() }
                NEW_ID -> followsRepo.unreadChapters(bucket).map { it.toPlayableItem() }
                else -> emptyList()
            }
            result.sendResult(items.toMutableList())
        }
    }

    private fun rootItems(): List<MediaBrowserCompat.MediaItem> = listOf(
        browsable(RESUME_ID, "Resume"),
        browsable(LIBRARY_ID, "Library"),
        browsable(FOLLOWS_ID, "Follows"),
        browsable(RECENT_ID, "Recent"),
        browsable(NEW_ID, "New chapters"),
    )

    private fun browsable(id: String, title: String): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    companion object {
        const val ROOT_ID = "/"
        const val RESUME_ID = "/resume"
        const val LIBRARY_ID = "/library"
        const val FOLLOWS_ID = "/follows"
        const val RECENT_ID = "/recent"
        const val NEW_ID = "/new"
        const val MAX_PER_CATEGORY = 6
    }
}

private fun `in`.jphe.storyvox.data.repository.playback.LibraryItem.toBrowsableItem():
    MediaBrowserCompat.MediaItem {
    val desc = MediaDescriptionCompat.Builder()
        .setMediaId("/library/$id")
        .setTitle(title)
        .setSubtitle(author)
        .setIconUri(coverUrl?.let { android.net.Uri.parse(it) })
        .build()
    return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
}

private fun `in`.jphe.storyvox.data.repository.playback.FollowItem.toBrowsableItem():
    MediaBrowserCompat.MediaItem {
    val desc = MediaDescriptionCompat.Builder()
        .setMediaId("/follows/$id")
        .setTitle(title)
        .setSubtitle(author)
        .setIconUri(coverUrl?.let { android.net.Uri.parse(it) })
        .build()
    return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
}

private fun `in`.jphe.storyvox.data.repository.playback.RecentItem.toPlayableItem():
    MediaBrowserCompat.MediaItem {
    val desc = MediaDescriptionCompat.Builder()
        .setMediaId("/recent/$fictionId/$chapterId")
        .setTitle(chapterTitle)
        .setSubtitle(bookTitle)
        .setIconUri(coverUrl?.let { android.net.Uri.parse(it) })
        .build()
    return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
}

private fun `in`.jphe.storyvox.data.repository.playback.UnreadChapter.toPlayableItem():
    MediaBrowserCompat.MediaItem {
    val desc = MediaDescriptionCompat.Builder()
        .setMediaId("/new/$fictionId/$chapterId")
        .setTitle(chapterTitle)
        .setSubtitle(bookTitle)
        .setIconUri(coverUrl?.let { android.net.Uri.parse(it) })
        .build()
    return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
}
