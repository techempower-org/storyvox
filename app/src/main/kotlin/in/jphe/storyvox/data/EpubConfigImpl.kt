package `in`.jphe.storyvox.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.epub.config.EpubConfig
import `in`.jphe.storyvox.source.epub.config.EpubEntryKind
import `in`.jphe.storyvox.source.epub.config.EpubFileEntry
import `in`.jphe.storyvox.source.epub.config.fictionIdForEpubUri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.epubDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_epub")

private object EpubKeys {
    /** Persisted SAF tree URI (the user-picked folder). The
     *  ContentResolver.takePersistableUriPermission grant survives
     *  reboots — we don't have to re-prompt the user. Empty / missing
     *  = no folder configured (Browse → Local Books shows empty
     *  state with a CTA back to Settings). */
    val FOLDER_URI = stringPreferencesKey("pref_epub_folder_uri")

    /** Issue #1000 — single files imported via "Open With" (vs the
     *  folder picker). Each member is one encoded entry record
     *  (`kind|displayName|uriString` — see [encodeImported] /
     *  [decodeImported]). A `Set<String>` preference dedups by exact
     *  record, but we also key the indexed list on fictionId so re-
     *  opening the same document doesn't create a duplicate fiction. */
    val IMPORTED_FILES = stringSetPreferencesKey("pref_epub_imported_files")
}

/** Issue #1000 — encoded `Set<String>` record for one imported file.
 *  Pipe-delimited; the uriString is last so it can legally contain the
 *  delimiter (we `substringAfter` the second pipe). */
private fun encodeImported(entry: EpubFileEntry): String =
    "${entry.kind.name}|${entry.displayName.replace('|', '_')}|${entry.uriString}"

private fun decodeImported(record: String): EpubFileEntry? {
    val firstPipe = record.indexOf('|')
    if (firstPipe < 0) return null
    val secondPipe = record.indexOf('|', firstPipe + 1)
    if (secondPipe < 0) return null
    val kindStr = record.substring(0, firstPipe)
    val displayName = record.substring(firstPipe + 1, secondPipe)
    val uriString = record.substring(secondPipe + 1)
    if (uriString.isBlank()) return null
    val kind = runCatching { EpubEntryKind.valueOf(kindStr) }.getOrNull() ?: return null
    return EpubFileEntry(
        fictionId = fictionIdForEpubUri(uriString),
        uriString = uriString,
        displayName = displayName,
        kind = kind,
    )
}

/** Decode the persisted import-record set into entries, sorted by
 *  display name. Malformed records are dropped silently (forward-
 *  compat / corruption resilience). */
private fun decodeImportedSet(records: Set<String>?): List<EpubFileEntry> =
    records.orEmpty()
        .mapNotNull { decodeImported(it) }
        .sortedBy { it.displayName.lowercase() }

/** Issue #1000 — merge folder-enumerated EPUBs with single-file
 *  imports. Folder entries win on fictionId collision (the folder is
 *  the canonical surface); imports for files not in the folder are
 *  appended. Stable order: folder books first, then imports, each
 *  alphabetised by its own producer. */
private fun mergeBooks(
    folder: List<EpubFileEntry>,
    imported: List<EpubFileEntry>,
): List<EpubFileEntry> {
    if (imported.isEmpty()) return folder
    val folderIds = folder.mapTo(HashSet()) { it.fictionId }
    return folder + imported.filter { it.fictionId !in folderIds }
}

/**
 * Issue #235 — abstraction over SAF folder enumeration + EPUB byte
 * reads. Production impl lives in this file; test fakes can pass a
 * no-op implementation and avoid pulling Robolectric into the
 * settings-test classpath.
 */
internal interface EpubFileReader {
    fun enumerate(treeUriString: String): List<EpubFileEntry>
    suspend fun readBytes(uriString: String): ByteArray?
}

private class SafEpubFileReader(
    private val context: Context,
) : EpubFileReader {

    override fun enumerate(treeUriString: String): List<EpubFileEntry> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return emptyList()
        if (!tree.isDirectory) return emptyList()
        return tree.listFiles()
            .asSequence()
            .filter { it.isFile && (it.name?.endsWith(".epub", ignoreCase = true) ?: false) }
            .mapNotNull { f ->
                val name = f.name ?: return@mapNotNull null
                val uriStr = f.uri.toString()
                EpubFileEntry(
                    fictionId = fictionIdForEpubUri(uriStr),
                    uriString = uriStr,
                    displayName = name,
                )
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }

    override suspend fun readBytes(uriString: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
            }.getOrNull()
        }
}

/**
 * Production [EpubConfig] (issue #235) backed by a tiny dedicated
 * DataStore + Android's Storage Access Framework. Same pattern as
 * [PalaceConfigImpl] / [RssConfigImpl] — the source module stays
 * Android-types-free; this impl owns the SAF plumbing.
 *
 * Folder traversal is delegated to [EpubFileReader] so the settings
 * tests can inject a no-op reader without bringing Robolectric +
 * Context into their classpath.
 */
@Singleton
class EpubConfigImpl internal constructor(
    private val store: DataStore<Preferences>,
    private val reader: EpubFileReader,
) : EpubConfig {

    @Inject constructor(@ApplicationContext context: Context) : this(
        store = context.epubDataStore,
        reader = SafEpubFileReader(context),
    )

    override val folderUriString: Flow<String?> = store.data
        .map { prefs -> prefs[EpubKeys.FOLDER_URI]?.takeIf { it.isNotBlank() } }
        .distinctUntilChanged()

    override suspend fun snapshot(): String? =
        store.data.first()[EpubKeys.FOLDER_URI]?.takeIf { it.isNotBlank() }

    /** Issue #1000 — single files imported via "Open With", decoded
     *  from the [EpubKeys.IMPORTED_FILES] preference set. Independent of
     *  the folder picker; merged into [books] below. */
    private val importedFiles: Flow<List<EpubFileEntry>> = store.data
        .map { prefs -> decodeImportedSet(prefs[EpubKeys.IMPORTED_FILES]) }
        .distinctUntilChanged()

    override val books: Flow<List<EpubFileEntry>> =
        combine(folderUriString, importedFiles) { uri, imported ->
            val folder = if (uri == null) emptyList()
            else withContext(Dispatchers.IO) { reader.enumerate(uri) }
            mergeBooks(folder, imported)
        }.distinctUntilChanged()

    override suspend fun books(): List<EpubFileEntry> {
        val folder = snapshot()?.let { withContext(Dispatchers.IO) { reader.enumerate(it) } }
            ?: emptyList()
        val imported = decodeImportedSet(store.data.first()[EpubKeys.IMPORTED_FILES])
        return mergeBooks(folder, imported)
    }

    override suspend fun readBookBytes(uriString: String): ByteArray? =
        reader.readBytes(uriString)

    /**
     * Mutator hooks for Settings UI. Kept on the impl rather than the
     * EpubConfig interface — same reasoning as [PalaceConfigImpl].
     * The caller is expected to take persistable URI permission via
     * [android.content.ContentResolver.takePersistableUriPermission]
     * before calling [setFolder]; otherwise the URI grant evaporates
     * on next app launch.
     */
    suspend fun setFolder(uriString: String) {
        store.edit { prefs ->
            if (uriString.isBlank()) prefs.remove(EpubKeys.FOLDER_URI)
            else prefs[EpubKeys.FOLDER_URI] = uriString.trim()
        }
    }

    suspend fun clearFolder() {
        store.edit { prefs -> prefs.remove(EpubKeys.FOLDER_URI) }
    }

    /**
     * Issue #1000 — register a single document received via "Open With"
     * (ACTION_VIEW / ACTION_SEND) as a standalone fiction, independent
     * of the folder picker. The caller (MainActivity's import path) is
     * expected to have already taken persistable read permission on the
     * Uri via [android.content.ContentResolver.takePersistableUriPermission]
     * so the grant survives the next launch (re-opens from Library
     * work). Returns the stable fictionId the resolver navigates to.
     *
     * Idempotent on the Uri: the persisted record set is keyed by the
     * exact encoded record, and the indexed list dedups by fictionId
     * ([fictionIdForEpubUri] is a stable hash of the Uri), so opening
     * the same file twice doesn't create a second fiction.
     */
    suspend fun importFile(
        uriString: String,
        displayName: String,
        kind: EpubEntryKind,
    ): String {
        val entry = EpubFileEntry(
            fictionId = fictionIdForEpubUri(uriString),
            uriString = uriString.trim(),
            displayName = displayName.ifBlank { uriString.substringAfterLast('/') },
            kind = kind,
        )
        store.edit { prefs ->
            val existing = prefs[EpubKeys.IMPORTED_FILES].orEmpty()
            // Drop any prior record for the same fictionId (e.g. the
            // display name changed) before adding the fresh one.
            val pruned = existing.filterTo(mutableSetOf()) { rec ->
                decodeImported(rec)?.fictionId != entry.fictionId
            }
            pruned.add(encodeImported(entry))
            prefs[EpubKeys.IMPORTED_FILES] = pruned
        }
        return entry.fictionId
    }

    companion object {
        /** Test factory — no-op [EpubFileReader] for unit tests that
         *  just need the dependency to satisfy the constructor. */
        internal fun forTesting(store: DataStore<Preferences>): EpubConfigImpl =
            EpubConfigImpl(
                store = store,
                reader = object : EpubFileReader {
                    override fun enumerate(treeUriString: String): List<EpubFileEntry> = emptyList()
                    override suspend fun readBytes(uriString: String): ByteArray? = null
                },
            )
    }
}
