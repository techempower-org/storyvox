package `in`.jphe.storyvox.source.epub.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #235 — abstraction over the EPUB-source's persistent
 * folder selection. The implementation lives in `:app` because
 * persisting a SAF-tree URI requires Android-specific permission
 * plumbing (ContentResolver.takePersistableUriPermission); this
 * interface is what the source consumes.
 *
 * One folder URI per install, by design — the typical user has
 * a single Calibre / Audiobooks / Books folder. Multi-folder
 * support is a follow-up if anyone asks; the current shape is
 * cheaper to reason about and maps cleanly to the SAF picker UX.
 *
 * The folderUriString persists as `String` rather than `android.net.Uri`
 * so this interface stays free of Android types — keeps the source
 * module testable without Robolectric.
 */
interface EpubConfig {
    /** Hot stream of the currently-configured folder URI string.
     *  Null when no folder has been picked. */
    val folderUriString: Flow<String?>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun snapshot(): String?

    /** Hot stream of indexed EPUB files in the folder. Each entry's
     *  [EpubFileEntry.uriString] is what the source pulls bytes from
     *  via the app-side EpubReader. */
    val books: Flow<List<EpubFileEntry>>

    /** Synchronous snapshot of the indexed-books list. */
    suspend fun books(): List<EpubFileEntry>

    /** Read the raw bytes of one EPUB. Implementations resolve
     *  [uriString] via SAF (DocumentFile + ContentResolver). Returns
     *  null on read failure (file moved, permission revoked, etc). */
    suspend fun readBookBytes(uriString: String): ByteArray?
}

/**
 * Issue #1000 — what kind of document an indexed entry is, so the
 * source knows how to turn its bytes into a book. Folder-enumerated
 * entries are always [Epub]; the "Open With" import path (#1000) can
 * also register a [Text] file, which the source wraps into a single-
 * chapter book rather than running through the EPUB zip parser.
 */
enum class EpubEntryKind {
    /** A real `.epub` — parse the zip/OPF/spine. */
    Epub,

    /** A plaintext file — synthesise a one-chapter book from the UTF-8
     *  body (the reader/engine pipeline downstream is HTML-tolerant, so
     *  the body is wrapped in a `<pre>` block). */
    Text,
}

/**
 * One indexed EPUB file. The fictionId is a stable hash of the URI
 * string — same file at the same SAF path resolves to the same id
 * across re-launches. Display name comes from DocumentFile.getName()
 * (just the filename) until [EpubFictionSource.fictionDetail] hydrates
 * with the real title parsed from the OPF.
 */
data class EpubFileEntry(
    val fictionId: String,
    val uriString: String,
    val displayName: String,
    /** Issue #1000 — how to read this entry's bytes into a book.
     *  Defaults to [EpubEntryKind.Epub] so existing folder-enumeration
     *  call sites (and any persisted state) keep their behaviour. */
    val kind: EpubEntryKind = EpubEntryKind.Epub,
)

/** Issue #235 — derive the persistent fictionId from a SAF URI.
 *  Stable across re-launches; collision-resistant for the small N
 *  (a few hundred EPUB files at most per user) storyvox cares about. */
fun fictionIdForEpubUri(uriString: String): String {
    val canonical = uriString.trim()
    val hash = canonical.hashCode().toUInt().toString(16).padStart(8, '0')
    return "epub:$hash"
}
