package `in`.jphe.storyvox.data.source.plugin

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugin-seam Phase 1 (#384) — unit tests for [SourcePluginRegistry].
 *
 * Verifies the public contract documented in the registry's kdoc:
 * - Empty input → empty `all`, `byId` returns null, `byCategory` empty.
 * - Sort order is `category.ordinal` then `displayName` case-insensitive.
 * - `byCategory` filters correctly.
 * - `byId` resolves to the right descriptor.
 *
 * Constructed directly with fakes — no Hilt / no KSP — per the
 * "test hook" pattern documented on [SourcePluginRegistry].
 */
class SourcePluginRegistryTest {

    @Test fun `empty registry surfaces no plugins`() {
        val registry = SourcePluginRegistry(emptySet())

        assertTrue(registry.all.isEmpty())
        assertEquals(emptyList<String>(), registry.ids)
        assertFalse(registry.isNotEmpty)
        assertNull(registry.byId("anything"))
        assertTrue(registry.byCategory(SourceCategory.Text).isEmpty())
    }

    @Test fun `single descriptor is surfaced and lookable by id`() {
        val descriptor = descriptor(id = "kvmr", displayName = "KVMR", category = SourceCategory.AudioStream)
        val registry = SourcePluginRegistry(setOf(descriptor))

        assertEquals(listOf(descriptor), registry.all)
        assertEquals(listOf("kvmr"), registry.ids)
        assertTrue(registry.isNotEmpty)
        assertEquals(descriptor, registry.byId("kvmr"))
        assertNull(registry.byId("notion"))
        assertEquals(listOf(descriptor), registry.byCategory(SourceCategory.AudioStream))
        assertTrue(registry.byCategory(SourceCategory.Text).isEmpty())
    }

    @Test fun `sort order is by category ordinal then displayName case-insensitive`() {
        // Text category comes BEFORE AudioStream (enum order).
        // Within Text, "ao3" < "GitHub" < "Royal Road" lowercased.
        val ao3 = descriptor(id = "ao3", displayName = "ao3", category = SourceCategory.Text)
        val github = descriptor(id = "github", displayName = "GitHub", category = SourceCategory.Text)
        val rr = descriptor(id = "rr", displayName = "Royal Road", category = SourceCategory.Text)
        val kvmr = descriptor(id = "kvmr", displayName = "KVMR", category = SourceCategory.AudioStream)

        // Intentionally feed in non-sorted order — registry should sort.
        val registry = SourcePluginRegistry(setOf(kvmr, rr, ao3, github))

        assertEquals(listOf("ao3", "github", "rr", "kvmr"), registry.ids)
    }

    @Test fun `byCategory respects sort within category`() {
        val a = descriptor(id = "a", displayName = "Apple", category = SourceCategory.Ebook)
        val b = descriptor(id = "b", displayName = "banana", category = SourceCategory.Ebook)
        val c = descriptor(id = "c", displayName = "Cherry", category = SourceCategory.Ebook)

        val registry = SourcePluginRegistry(setOf(c, a, b))

        assertEquals(listOf("a", "b", "c"), registry.byCategory(SourceCategory.Ebook).map { it.id })
    }

    @Test fun `descriptor carries the live FictionSource instance`() {
        val source = FakeFictionSource(id = "fake")
        val descriptor = SourcePluginDescriptor(
            id = "fake",
            displayName = "Fake",
            defaultEnabled = false,
            category = SourceCategory.Other,
            supportsFollow = false,
            supportsSearch = false,
            source = source,
        )

        val registry = SourcePluginRegistry(setOf(descriptor))

        val found = registry.byId("fake")
        assertNotNull(found)
        assertEquals(source, found!!.source)
    }

    @Test fun `phase 2 expected roster of 12 plugins resolves end-to-end`() {
        // Plugin-seam Phase 2 (#384) — after the 11-backend migration
        // the registry should surface the full storyvox roster: the
        // original audio-stream backend (now :source-radio, was
        // :source-kvmr; #417) + the 11 fiction backends migrated here.
        // This test exercises the registry contract with a synthetic
        // roster matching the @SourcePlugin annotations declared across
        // the source modules; the actual KSP-generated bindings are
        // verified at compile time by `:app:assembleDebug` (Hilt would
        // fail to build the graph if any descriptor were missing or
        // duplicated).
        val expectedIds = listOf(
            SourceIds.ROYAL_ROAD,
            SourceIds.GITHUB,
            SourceIds.MEMPALACE,
            SourceIds.RSS,
            SourceIds.EPUB,
            SourceIds.OUTLINE,
            SourceIds.GUTENBERG,
            SourceIds.AO3,
            SourceIds.STANDARD_EBOOKS,
            SourceIds.WIKIPEDIA,
            // Issue #417 — RADIO replaces KVMR in the canonical
            // descriptor registry (the @SourcePlugin annotation moved
            // with the rename); KVMR survives only as a routing alias
            // in the FictionSource map binding, not in the descriptor
            // registry.
            SourceIds.RADIO,
            // Issue #770 — split NotionSource into TechEmpower + PAT.
            // The legacy NOTION id survives as a routing alias in the
            // FictionSource map binding, not in the descriptor registry.
            SourceIds.NOTION_TECHEMPOWER,
            SourceIds.NOTION_PAT,
        )
        val descriptors = expectedIds.map { id ->
            descriptor(
                id = id,
                displayName = id,
                category = if (id == SourceIds.RADIO) SourceCategory.AudioStream else SourceCategory.Text,
            )
        }

        val registry = SourcePluginRegistry(descriptors.toSet())

        assertEquals(13, registry.all.size)
        // Every expected id resolves via byId — order-independent so
        // the assertion stays robust against future sort changes.
        for (id in expectedIds) {
            assertNotNull("Expected plugin id '$id' missing from registry", registry.byId(id))
        }
        // ids list is non-empty and matches the descriptor set.
        assertEquals(expectedIds.toSet(), registry.ids.toSet())
    }

    @Test fun `duplicate ids fail fast at registry construction`() {
        // Plugin-seam Phase 2 (#384) — two @SourcePlugin annotations
        // colliding on the same id must surface as a hard failure at
        // app startup, not as silent which-one-wins behaviour. The
        // init-block check on the registry catches it.
        val first = descriptor(id = "dupe", displayName = "First", category = SourceCategory.Text)
        val second = descriptor(id = "dupe", displayName = "Second", category = SourceCategory.Text)

        val ex = assertThrows(IllegalStateException::class.java) {
            SourcePluginRegistry(setOf(first, second))
        }
        assertTrue(
            "Expected message to mention the duplicate id, got: ${ex.message}",
            ex.message?.contains("dupe") == true,
        )
    }

    // ─── fixtures ─────────────────────────────────────────────────

    private fun descriptor(
        id: String,
        displayName: String,
        category: SourceCategory,
    ): SourcePluginDescriptor = SourcePluginDescriptor(
        id = id,
        displayName = displayName,
        defaultEnabled = false,
        category = category,
        supportsFollow = false,
        supportsSearch = false,
        source = FakeFictionSource(id),
    )

    private class FakeFictionSource(override val id: String) : FictionSource {
        override val displayName: String = id
        override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
            FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
            popular(page)
        override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
            popular(page)
        override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
            popular(1)
        override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> =
            FictionResult.NotFound("fake")
        override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> =
            FictionResult.NotFound("fake")
        override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> = popular(page)
        override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
            FictionResult.Success(Unit)
        override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(emptyList())
    }
}
