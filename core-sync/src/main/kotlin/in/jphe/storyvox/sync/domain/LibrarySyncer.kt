package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.FictionShelfDao
import `in`.jphe.storyvox.data.db.dao.PlaybackDao
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import `in`.jphe.storyvox.sync.coordinator.TombstoneStore
import `in`.jphe.storyvox.sync.coordinator.TombstonesAccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs the user's library set — the set of `fictionId`s that the user
 * has marked "Add to library."
 *
 * Note on what does NOT sync via this syncer:
 *  - The fiction metadata itself (title, author, cover, etc.). Each
 *    device re-fetches metadata from the originating source, so we
 *    only need to know "the user added X" — we don't ship row contents
 *    over the wire.
 *  - The chapter rows / chapter bodies / download state. Those are
 *    purely local — chapter text gets re-fetched, download state is
 *    a device-local concern. (A future "chapter bookmarks" syncer
 *    handles the user-meaningful chapter state — see
 *    [PronunciationDictSyncer] for the LWW blob shape that one will
 *    take.)
 *
 * The strategy is delegated to [SetSyncer] — union with tombstones.
 */
@Singleton
class LibrarySyncer @Inject constructor(
    private val fictionDao: FictionDao,
    private val shelfDao: FictionShelfDao,
    private val playbackDao: PlaybackDao,
    private val backend: InstantBackend,
    private val tombstones: TombstoneStore,
) : Syncer {

    private val delegate by lazy { buildDelegate() }

    override val name: String get() = DOMAIN

    override suspend fun push(user: SignedInUser): SyncOutcome = delegate.push(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = delegate.pull(user)

    /** Record that the user removed a fiction from their library. Called
     *  from the FictionRepository's remove-from-library path so the
     *  removal propagates on next sync. */
    suspend fun recordRemoval(fictionId: String) {
        tombstones.add(DOMAIN, fictionId)
    }

    private fun buildDelegate(): SetSyncer = SetSyncer(
        name = DOMAIN,
        tombstones = tombstones,
        localSnapshot = {
            // We're treating "id is in the user's library" as the set
            // members. The fiction row itself is created/upserted when
            // metadata is fetched — the syncer just flips `inLibrary` on
            // a known row, OR (for a fresh install pulling on first
            // sign-in) records the id with no row yet. The fiction
            // detail page will lazy-load the metadata on first open,
            // which mirrors how it already works after a clear-cache.
            fictionDao.librarySnapshot().map { it.id }.toSet()
        },
        localAdd = { id ->
            val existing = fictionDao.get(id)
            if (existing == null) {
                // No row yet — the user added this on another device. We
                // insert a minimal placeholder so the library UI can
                // surface it; the source layer will fill in metadata on
                // first browse. Required fields get sentinel values
                // that the UI treats as "loading" (see existing
                // `FictionDetailScreen` skeleton handling for clear-cache
                // recovery).
                val now = System.currentTimeMillis()
                fictionDao.upsert(
                    `in`.jphe.storyvox.data.db.entity.Fiction(
                        id = id,
                        // Issue #981 — shape-aware sourceId. The old
                        // `id.substringBefore(':')` stored the whole number
                        // for bare Royal Road ids (no colon), yielding an
                        // unbindable sourceId that left the row stuck on
                        // "Loading…" forever. resolveByShape returns
                        // "royalroad" for colon-less ids; the back-fill
                        // worker repairs the rarer radio-station case.
                        sourceId = `in`.jphe.storyvox.data.source.FictionSourceIdResolver.resolveByShape(id),
                        title = "Loading…",
                        author = "",
                        firstSeenAt = now,
                        metadataFetchedAt = 0L,
                        inLibrary = true,
                        addedToLibraryAt = now,
                    ),
                )
            } else {
                fictionDao.setInLibrary(id, inLibrary = true, now = System.currentTimeMillis())
            }
        },
        localRemove = { id ->
            // A cross-device removal flips the row out of the library, but
            // we also have to tear down the satellite rows that a local
            // remove-from-library would have cleaned up — otherwise they
            // linger as invisible orphans (issue #891):
            //  - shelf memberships: a "shelved but un-libraried" row is
            //    filtered out of chip results, but it's still on disk and
            //    survives a future re-add, silently re-shelving the book.
            //  - playback position: stale offset/speed data that would
            //    resurface on the History tab and Continue-listening tile.
            // chapter_history is deliberately NOT touched — reading history
            // is conservative state we keep across a removal.
            fictionDao.setInLibrary(id, inLibrary = false, now = 0L)
            shelfDao.clearForFiction(id)
            playbackDao.delete(id)
        },
        remote = BackendSetRemote(domain = DOMAIN, backend = backend),
        // Issue #989 — carry the rebuild URL for hash-id sources
        // (Readability/RSS/EPUB-direct) so a synced library member can be
        // reconstructed on a device that never saw the paste. Only those
        // sources persist a `sourceUrl`; everything else returns null and
        // is naturally absent from the payload.
        localMemberData = { ids ->
            ids.mapNotNull { id -> fictionDao.getSourceUrl(id)?.let { id to it } }.toMap()
        },
        persistMemberData = { id, url ->
            // Stamp the URL onto the placeholder row SetSyncer just
            // created. setSourceUrlIfAbsent never clobbers a URL this
            // device already captured first-hand.
            fictionDao.setSourceUrlIfAbsent(id, url)
        },
    )

    companion object {
        const val DOMAIN: String = "library"
    }
}
