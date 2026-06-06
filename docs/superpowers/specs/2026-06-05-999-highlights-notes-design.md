# #999 — Text highlights + notes (annotation) with export

**Status:** design (awaiting team-lead scope ruling)
**Issue:** techempower-org/candela#999 (extends #121 per-chapter bookmark)
**Branch:** `feat/999-highlights-notes` / worktree `echo-999`

## Problem

storyvox today has only a single per-chapter bookmark (`Chapter.bookmarkCharOffset`,
#121). No text-range highlights, no notes, no multi-bookmark. The issue (VDR
competitive feature) wants: highlight a passage, attach an optional note, see a
per-fiction list of highlights/notes, and export them (Markdown/TXT via the
existing share-sheet FileProvider). Sync mirrors `BookmarksSyncer`.

## Range model (the one real design decision)

The reader already operates on `chapterText: String` with char offsets:
`Chapter.bookmarkCharOffset`, `state.sentenceStart/sentenceEnd`, and
`TextLayoutResult` in `ReaderView.kt`. An annotation's range is therefore
**`[startOffset, endOffset)` as character offsets into the same chapterText** —
no new coordinate concept. This is consistent with the existing bookmark and
sentence-highlight, and it is what the reader's `TextLayoutResult` already maps
to/from. Stored text snippet (`quotedText`) is denormalized into the row so the
export and the list survive a chapter re-fetch that shifts offsets (offsets are
best-effort for scroll-to; the quoted text is the durable record).

## Architecture (well-bounded units)

### 1. core-data: `Annotation` entity + `AnnotationDao` (new table, v14)
- `@Entity(tableName = "annotation")`, FK `fictionId → fiction(id)` ON DELETE CASCADE,
  FK `chapterId → chapter(id)` ON DELETE CASCADE.
- Columns: `id` TEXT PK (client-generated UUID — stable sync key, mirrors
  `SyncIds` usage), `fictionId` TEXT, `chapterId` TEXT, `startOffset` INTEGER,
  `endOffset` INTEGER, `color` TEXT (palette enum-name string, same
  string-not-ordinal pattern as `FictionShelf.shelf`), `note` TEXT nullable
  (null = highlight with no note), `quotedText` TEXT (denormalized snippet),
  `createdAt` INTEGER, `updatedAt` INTEGER (for LWW sync + edit).
- Indices: `index_annotation_fictionId` (per-fiction list), `index_annotation_chapterId`
  (FK cascade planner + reader-per-chapter overlay query).
- DAO patterned on `FictionShelfDao`: `observeForFiction(fictionId): Flow<List<Annotation>>`,
  `observeForChapter(chapterId): Flow<List<Annotation>>`, `allAnnotations()` (sync),
  `upsert(@Insert REPLACE)`, `deleteById(id)`, `exists(id)`, `clearForFiction(fictionId)`.

### 2. core-data: `MIGRATION_13_14` + DB bump to v14
- `CREATE TABLE IF NOT EXISTS annotation(...)` matching the entity byte-for-byte
  (so `runMigrationsAndValidate` identity hash passes) + the two `CREATE INDEX`.
- Register in `ALL_MIGRATIONS`, add `Annotation::class` + `annotationDao()` to
  `StoryvoxDatabase`, bump `version = 14`, regenerate `14.json` schema (KSP).

### 3. core-data test: migration coverage
- `migrate v13 to v14 creates annotation table` — table + both indexes exist.
- `migrated v14 db round-trips an annotation row` — insert+read through the
  migrated DB (catches entity/schema mismatch the hash check wouldn't).
- Update the two existing "open at latest" tests' chain-through to add the
  `runMigrationsAndValidate(dbName, 14, true, MIGRATION_13_14)` step + the v14
  migration in their `Room.databaseBuilder().addMigrations(...)`.

### 4. core-sync: `AnnotationsSyncer` (mirrors `BookmarksSyncer`)
- One blob per user (`ENTITY="blobs"`, `rowId=SyncIds.rowUuid("annotations", userId)`),
  LWW on the whole map keyed by persisted `instantdb.annotations_synced_at`.
- Map is `id → AnnotationDto?` (null = explicit delete tombstone). Replicates the
  #1029/#360 contract exactly: **absence ≠ deletion**; only an explicit null clears
  a local row; local-only adds are unioned into the push so an unsynced add is
  never clobbered. FK guard: skip-but-retain-on-wire when the chapter/fiction row
  isn't hydrated locally yet (mirrors BookmarksSyncer's `chapterDao.exists`).
- Register in `SyncModule` `@IntoSet`.

### 5. feature/reader: select-text → highlight + note
- ReaderView already has `TextLayoutResult` + long-press word handling
  (`SentenceHighlight`/AI lookup). Add a selection-range action: on a selected
  range, show a small action bar → pick highlight color (+ optional note dialog).
  Maps the selected `TextRange` (composeoffsets) → char offsets into chapterText.
- Render existing annotations for the open chapter as a background span overlay
  in the AnnotatedString (color from the palette), layered under the
  sentence-playback highlight.

### 6. feature: per-fiction highlights/notes list + export
- A surface listing this fiction's annotations (grouped by chapter, ordered by
  startOffset), tap-to-jump-to-reader. Entry point on FictionDetail (where the
  EPUB export already lives).
- Export: build a Markdown/TXT file in `cacheDir/exports/` (the existing
  FileProvider scope), emit a one-shot UI event → share-sheet, EXACTLY mirroring
  `exportToEpub` + `EpubExported` event in FictionDetailViewModel/Screen.

## Error handling
- Offsets out of range after a chapter re-fetch: clamp/skip the overlay span,
  still show the row in the list via `quotedText` (durable). No crash.
- Sync FK-not-yet-hydrated: retain on wire, apply on a later round (BookmarksSyncer pattern).
- Export with zero annotations: disabled/short-circuited, friendly message.

## Testing
- core-data: the migration tests above (Robolectric + MigrationTestHelper).
- core-data: AnnotationDao round-trip (existing in-memory Room DAO test pattern).
- core-sync: `AnnotationsSyncerTest` mirroring `BookmarksSyncerTest` — LWW,
  local-only preservation (#1029), explicit-null delete, FK-skip-retain.
- feature: offset-mapping pure function unit test (TextRange ↔ char offset),
  export-formatter pure function unit test (annotations → Markdown/TXT string).

## Scope forks for team-lead (need a ruling before build)
1. **Multi-bookmark promotion** — the issue lists "promote bookmarks from
   one-per-chapter to multiple labeled bookmarks while here." That's a *separate*
   schema/sync change (touches the chapter bookmark column + BookmarksSyncer wire
   format) and is a known data-migration risk. RECOMMEND: **out of this PR**
   (its own issue/PR), keep #999 to annotations only. Confirm.
2. **Highlight range = char offset into chapterText** (above). Confirm OK vs any
   paragraph-index model #1001 is introducing (rebase-coordinate with that work).
3. **List/export surface = FictionDetail** (reuses #117 export plumbing) vs a
   Library surface. RECOMMEND FictionDetail.
4. **Phase split** — issue suggests Phase 1 (highlights no notes) / Phase 2
   (notes+export). RECOMMEND: ship annotations *with* notes + export in one PR
   (the note column + export are cheap once the table exists); skip multi-bookmark.
   Confirm single-PR scope is acceptable.
