package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.source.notion.net.NotionBlock
import `in`.jphe.storyvox.source.notion.net.flattenNested
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1036 — nested Notion blocks (toggles, columns, synced blocks,
 * nested lists) were silently dropped because `pageBlocks` never
 * recursed on `has_children` and the renderer dropped container types.
 *
 * These tests exercise the two pure pieces of the fix without HTTP:
 *  - [flattenNested]: depth-first flatten of a block tree into the flat
 *    document-order list the rest of the pipeline consumes, stamping
 *    each block's nesting [NotionBlock.depth], honouring a depth cap and
 *    a total-request budget.
 *  - rendering of container blocks (column_list/column/synced_block) and
 *    depth-indented list items in [NotionBlock.toHtml]/[toPlainText].
 *
 * The fetch loop in `NotionApi.pageBlocks` wires a real
 * `GET /v1/blocks/{id}/children` fetcher into [flattenNested]; here we
 * pass an in-memory fetcher so the recursion logic is testable in pure
 * JVM with no network.
 */
class NotionNestedBlocksTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── flattenNested: depth-first splice ────────────────────────────

    @Test
    fun `flattenNested splices children depth-first right after their parent`() = runTest {
        // toggle "Parent" -> [child "A", child "B"], then sibling "After".
        val parent = container("toggle", "p1", hasChildren = true)
        val after = paragraph("After", "p2")
        val children = mapOf(
            "p1" to listOf(paragraph("A", "a1"), paragraph("B", "b1")),
        )
        val flat = flattenNested(
            roots = listOf(parent, after),
            fetchChildren = { id -> children[id].orEmpty() },
        )
        // Document order: parent, A, B, After.
        assertEquals(listOf("p1", "a1", "b1", "p2"), flat.map { it.id })
        // Parent + sibling at depth 0; spliced children at depth 1.
        assertEquals(listOf(0, 1, 1, 0), flat.map { it.depth })
    }

    @Test
    fun `flattenNested recurses through multiple levels`() = runTest {
        // L0 has child L1, which has child L2.
        val l0 = container("toggle", "l0", hasChildren = true)
        val children = mapOf(
            "l0" to listOf(container("toggle", "l1", hasChildren = true)),
            "l1" to listOf(paragraph("deep", "l2")),
        )
        val flat = flattenNested(listOf(l0)) { id -> children[id].orEmpty() }
        assertEquals(listOf("l0", "l1", "l2"), flat.map { it.id })
        assertEquals(listOf(0, 1, 2), flat.map { it.depth })
    }

    @Test
    fun `flattenNested does not fetch children for blocks without has_children`() = runTest {
        val leaf = paragraph("leaf", "x")
        var fetches = 0
        val flat = flattenNested(listOf(leaf)) { fetches++; emptyList() }
        assertEquals(listOf("x"), flat.map { it.id })
        assertEquals(0, fetches) // hasChildren=false → never asks the API
    }

    @Test
    fun `flattenNested honours the depth cap`() = runTest {
        // A chain deeper than the cap: each level claims a child.
        val root = container("toggle", "d0", hasChildren = true)
        val children = HashMap<String, List<NotionBlock>>()
        for (i in 0 until 10) {
            children["d$i"] = listOf(container("toggle", "d${i + 1}", hasChildren = true))
        }
        val flat = flattenNested(
            roots = listOf(root),
            maxDepth = 3,
            fetchChildren = { id -> children[id].orEmpty() },
        )
        // Cap at 3 means we descend to depth 3 and stop (don't fetch d4's kids).
        val maxSeen = flat.maxOf { it.depth }
        assertTrue("expected to stop at depth <= 3, saw $maxSeen", maxSeen <= 3)
    }

    @Test
    fun `flattenNested honours the total-request budget`() = runTest {
        // Wide tree: 1 root with 100 has_children siblings; tiny budget.
        val roots = (0 until 100).map { container("toggle", "n$it", hasChildren = true) }
        val children = roots.associate { it.id to listOf(paragraph("k", "k${it.id}")) }
        var fetches = 0
        flattenNested(
            roots = roots,
            maxRequests = 5,
            fetchChildren = { id -> fetches++; children[id].orEmpty() },
        )
        assertTrue("expected <= 5 child fetches, made $fetches", fetches <= 5)
    }

    // ─── rendering: containers + nested list indentation ──────────────

    @Test
    fun `column_list and column render as transparent containers`() {
        // They carry no text of their own — children render on their own.
        assertEquals("", NotionBlock(id = "cl", type = "column_list").toHtml())
        assertEquals("", NotionBlock(id = "c", type = "column").toHtml())
        assertEquals("", NotionBlock(id = "sb", type = "synced_block").toHtml())
        assertEquals("", NotionBlock(id = "cl", type = "column_list").toPlainText())
        assertEquals("", NotionBlock(id = "c", type = "column").toPlainText())
        assertEquals("", NotionBlock(id = "sb", type = "synced_block").toPlainText())
    }

    @Test
    fun `nested bulleted list item indents its html by depth`() {
        val nested = bullet("Sub-point", "b1").copy(depth = 1)
        // Depth-1 list item gets wrapped so the reader shows nesting.
        assertEquals(
            "<ul><li>Sub-point</li></ul>",
            nested.toHtml(),
        )
        // Plain text (TTS) is unaffected by indentation — text is what matters.
        assertEquals("Sub-point", nested.toPlainText())
    }

    @Test
    fun `top-level list item is not extra-indented`() {
        val top = bullet("Point", "b0") // depth 0
        assertEquals("<li>Point</li>", top.toHtml())
    }

    @Test
    fun `toggle body children are present in the flat list and narrate`() = runTest {
        // Real-world repro: a toggle whose body is the only content.
        val toggle = container("toggle", "t0", hasChildren = true).let {
            it.copy(toggle = json.parseToJsonElement("""{"rich_text":[{"plain_text":"Summary"}]}"""))
        }
        val children = mapOf("t0" to listOf(paragraph("Hidden body text.", "p1")))
        val flat = flattenNested(listOf(toggle)) { id -> children[id].orEmpty() }
        val narration = flat.joinToString(" ") { it.toPlainText() }.trim()
        assertTrue(
            "toggle body must appear in narration, got: '$narration'",
            narration.contains("Hidden body text."),
        )
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private fun paragraph(text: String, id: String) = NotionBlock(
        id = id,
        type = "paragraph",
        paragraph = json.parseToJsonElement("""{"rich_text":[{"plain_text":"$text"}]}"""),
    )

    private fun bullet(text: String, id: String) = NotionBlock(
        id = id,
        type = "bulleted_list_item",
        bulletedListItem = json.parseToJsonElement("""{"rich_text":[{"plain_text":"$text"}]}"""),
    )

    private fun container(type: String, id: String, hasChildren: Boolean) = NotionBlock(
        id = id,
        type = type,
        hasChildren = hasChildren,
    )
}
