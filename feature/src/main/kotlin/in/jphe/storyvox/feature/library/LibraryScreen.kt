package `in`.jphe.storyvox.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.TechEmpowerLinks
import `in`.jphe.storyvox.data.db.entity.InboxEvent
import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.data.repository.HistoryEntry
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.feature.browse.BrowseScreen
import `in`.jphe.storyvox.feature.follows.FollowsScreen
import `in`.jphe.storyvox.feature.techempower.TechEmpowerHelpIcons
import androidx.compose.ui.draw.clip
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.BrassProgressBar
import `in`.jphe.storyvox.ui.component.coverSourceFamilyFor
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.cascadeReveal
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenFiction: (String) -> Unit,
    onOpenReader: (String, String) -> Unit,
    onOpenSettings: () -> Unit = {},
    /**
     * Issue #472 — when non-null, opens the Magic-add sheet pre-filled
     * with this URL on first composition. Set by the share-intent path
     * via `DeepLinkResolver.ARG_SHARED_URL`. Default null (normal
     * Library open).
     */
    sharedUrl: String? = null,
    /**
     * Issue #383 — Inbox row tap. Carries a fully-resolved deep-link URI
     * string (`storyvox://reader/<fid>/<cid>` or `storyvox://fiction/<fid>`).
     * The host decodes it. Default no-op so existing call sites that
     * haven't been updated still compile; the production wiring in
     * the app activity should always supply this.
     */
    onOpenInboxLink: (String) -> Unit = {},
    /**
     * Restructure (v0.5.40) — embedded FollowsScreen's sign-in CTA
     * (Royal Road WebView for now; #241 shared sign-in surface).
     *
     * v0.5.72 — Browse was promoted to a first-class bottom-nav tab,
     * so the previously-threaded `onOpenRoyalRoadSignIn` and
     * `onOpenAo3SignIn` callbacks have been dropped here (Browse's
     * standalone route owns them now). Follows stays embedded under
     * Library and still routes through here.
     */
    onOpenFollowsSignIn: () -> Unit = {},
    /**
     * Issue #500 — InstantDB sync entry point. Default no-op so test /
     * preview surfaces that don't exercise the sync sheet still
     * compile. The production wiring in [StoryvoxNavHost] routes this
     * to [StoryvoxRoutes.SYNC]; the cloud icon in the top app bar
     * lights up via [SyncCloudIcon] independently of the callback.
     */
    onOpenSync: () -> Unit = {},
    /**
     * Issue #517 — TechEmpower hero card tap. Routes to
     * [StoryvoxRoutes.TECHEMPOWER_HOME]. Default no-op so test /
     * preview surfaces that don't exercise the hero still compile.
     */
    onOpenTechEmpower: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addByUrlState by viewModel.addByUrlState.collectAsStateWithLifecycle()
    val manageShelvesState by viewModel.manageShelvesState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    // #328 — dedupe defensively before the LazyVerticalGrid sees the list.
    // Compose enforces unique item keys; duplicates throw and crash the
    // activity. Hoisted out of the grid builder via remember so the
    // distinctBy pass runs once per state.fictions change instead of on
    // every recomposition. Logs at warn level when duplicates are dropped
    // so the underlying source can be traced — silent-by-default would
    // hide the upstream bug we're guarding around.
    // Issue #500 — local sheet visibility for the cloud-icon affordance.
    // The sheet itself owns its own VM that reads InstantSession +
    // SyncCoordinator state, so the Library screen only tracks the
    // open/closed boolean here.
    var syncSheetOpen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    val dedupedFictions = androidx.compose.runtime.remember(state.fictions) {
        val out = state.fictions.distinctBy { it.id }
        val dropped = state.fictions.size - out.size
        if (dropped > 0) {
            android.util.Log.w(
                "storyvox",
                "LibraryScreen: dropped $dropped duplicate fiction id(s) (size ${state.fictions.size} -> ${out.size}) — see #328",
            )
        }
        out
    }

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryUiEvent.OpenFiction -> onOpenFiction(event.fictionId)
                is LibraryUiEvent.OpenReader -> onOpenReader(event.fictionId, event.chapterId)
                is LibraryUiEvent.OpenInboxLink -> onOpenInboxLink(event.deepLinkUri)
            }
        }
    }

    Scaffold(
        // Issue #255 — Library used to fade straight from the system status
        // bar into a Resume card with no header — no 'Library' title, no
        // identity. CenterAlignedTopAppBar gives the screen a hard
        // identifier (matches Material 3 standard for home tabs) and a
        // place to surface search/sort/filter actions later. For now the
        // bar is bare title-only; the issue's deeper ask (search +
        // sort/filter affordances) needs its own follow-up since neither
        // exists in LibraryViewModel yet.
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Library", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    // Issue #533 — top-bar action icons used to pack
                    // flush together at 0dp gap on the Flip3 (1080dp
                    // narrow). Four 48dp Boxes (2x TechEmpower help +
                    // SyncCloudIcon + Settings IconButton) lined up
                    // edge-to-edge made mis-taps trivial. Inserting an
                    // 8dp Spacer between each icon adds the visual
                    // breathing room (and tap-target separation) that
                    // Material 3 spec recommends for grouped action
                    // icons without bumping the row past Flip3 width.
                    //
                    // Issue #517 — TechEmpower help icons (phone +
                    // Discord) — leftmost so the cross-cutting "I
                    // need help" affordances read before the
                    // engine-specific cloud-icon and settings gear.
                    // Phone is tap = 211, long-press = 988; Discord
                    // opens the peer-support invite URL. See
                    // [TechEmpowerHelpIcons] for the design rationale.
                    TechEmpowerHelpIcons()
                    Spacer(Modifier.width(spacing.xs))
                    // Issue #500 — brass cloud-icon affordance for the
                    // InstantDB sync surface. Sits to the LEFT of the
                    // Settings gear so the "primary" cross-cutting state
                    // (am I synced?) reads before the secondary (settings).
                    // The three icon states (signed-in checkmark / spinner /
                    // question-mark) drive off [SyncStatusViewModel] —
                    // see [SyncCloudIcon] for the mapping. Tap opens
                    // [SyncStatusSheet] inline.
                    `in`.jphe.storyvox.feature.sync.SyncCloudIcon(
                        onClick = { syncSheetOpen = true },
                    )
                    Spacer(Modifier.width(spacing.xs))
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::showAddByUrl,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add fiction by URL")
            }
        },
    ) { scaffoldPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
            // Issue #158 — Library sub-tabs. SecondaryScrollableTabRow
            // rather than the fixed SecondaryTabRow because the
            // restructure (v0.5.40) grew the tab count to five
            // (Library / Browse / Follows / Inbox / History) and the
            // labels do not fit the Flip3 portrait width laid out
            // evenly. Scrollable is the same pattern BrowseScreen
            // already uses for its narrow-viewport tab row, so this
            // stays inside the existing visual vocabulary.
            //
            // Layering with #116 (shelves): the chip row is shown only
            // on Tab.Library — on Tab.Browse / Follows the embedded
            // surfaces own their own scoping, on Tab.Inbox / History
            // the chip row isn't meaningful.
            // Issue #532 — the tab row used to use `edgePadding = 0.dp`
            // which lined the rightmost tab flush with the screen edge.
            // On the Flip3 cover (1080dp narrow) only 4 of the 5 tabs
            // fit visibly with no visual hint that "History" (the
            // fifth) is reachable by horizontal swipe. Bumping
            // edgePadding to spacing.md (16dp) lets the rightmost
            // visible tab land as an obviously-partial chip — the same
            // "scroll for more →" discoverability fix the
            // VoiceLibrary chip row uses (#420 + #534). Scroll
            // mechanics were already wired; this is the discovery fix.
            SecondaryScrollableTabRow(
                selectedTabIndex = state.tab.ordinal,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = spacing.md,
            ) {
                LibraryTab.entries.forEach { tab ->
                    val isSelected = state.tab == tab
                    Tab(
                        selected = isSelected,
                        onClick = { viewModel.selectTab(tab) },
                        // Issue #613 (v1.0 blocker) — Material3's Tab
                        // applies Role.Tab via its internal clickable on
                        // 1.2+, but earlier baselines + custom
                        // SecondaryScrollableTabRow layouts have been
                        // observed (R5CRB0W66MK semantics dump,
                        // 2026-05-15) dropping the role on the merged
                        // node. Belt-and-suspenders an explicit
                        // `role = Role.Tab` + `selected` here so the
                        // a11y tree always carries both, regardless of
                        // Material version. The semantics modifier
                        // comes BEFORE Tab's internal clickable so the
                        // role properties get merged in rather than
                        // overwritten.
                        modifier = Modifier.semantics {
                            role = Role.Tab
                            selected = isSelected
                        },
                        text = {
                            // Issue #383 — Inbox tab carries an unread-count
                            // badge. BadgedBox positions the badge at the
                            // top-right of the label without disturbing the
                            // tab row's alignment. Zero unread = no badge
                            // (same convention as FollowsScreen #290).
                            if (tab == LibraryTab.Inbox && state.inboxUnreadCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ) {
                                            Text(state.inboxUnreadCount.toString())
                                        }
                                    },
                                ) {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            } else {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        },
                    )
                }
            }

            when (state.tab) {
                LibraryTab.Library -> Column(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
                    // #116 — chip strip above the grid (and above the
                    // Resume card) so it's always reachable regardless of
                    // scroll. #438 collapsed the old `Reading` tab into a
                    // chip in this row, so this strip is the *only* nested
                    // navigation surface for shelves now — no more
                    // duplicate-label tab/chip pair.
                    ShelfChipRow(
                        selected = state.filter,
                        onSelect = viewModel::selectFilter,
                    )
                    // Issue #452 — LazyVerticalGrid inside a Column needs a
                    // bounded height constraint. Without `weight(1f)`, the
                    // grid was being measured with `Constraints.Infinity` on
                    // the inner axis: it survived in portrait because the
                    // parent Column's natural height happened to bound it,
                    // but in landscape (or Flip3 unfolded → wider-than-tall)
                    // the unbounded measurement collapsed the grid to zero
                    // rows. Wrapping in a Box with `weight(1f)` gives the
                    // grid a concrete bounded height in both orientations.
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LibraryGridBody(
                            state = state,
                            dedupedFictions = dedupedFictions,
                            onResume = viewModel::resume,
                            onOpenFiction = viewModel::openFiction,
                            onLongPress = viewModel::openManageShelves,
                            onOpenTechEmpower = onOpenTechEmpower,
                        )
                    }
                }

                // Restructure (v0.5.40) — Follows embedded under Library.
                LibraryTab.Follows -> Box(modifier = Modifier.fillMaxSize()) {
                    FollowsScreen(
                        onOpenFiction = onOpenFiction,
                        onOpenSignIn = onOpenFollowsSignIn,
                        onOpenSettings = onOpenSettings,
                        embedded = true,
                    )
                }

                LibraryTab.Inbox -> Box(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
                    InboxList(
                        events = state.inbox,
                        onOpenEvent = viewModel::openInboxEvent,
                        onMarkAllRead = viewModel::markAllInboxRead,
                    )
                }

                LibraryTab.History -> Box(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
                    HistoryList(
                        entries = state.history,
                        onOpenChapter = viewModel::openHistoryEntry,
                    )
                }
            }
        }
    }

    AddByUrlSheet(
        state = addByUrlState,
        onSubmit = viewModel::submitAddByUrl,
        onDismiss = viewModel::dismissAddByUrl,
        onChooseSource = viewModel::chooseSource,
        onCancelChoose = viewModel::cancelChooseSource,
    )

    ManageShelvesSheet(
        state = manageShelvesState,
        onToggle = viewModel::toggleShelf,
        onDismiss = viewModel::dismissManageShelves,
    )

    // Issue #500 — InstantDB sync status sheet. Mounted unconditionally
    // and gated on [syncSheetOpen] to keep the composition stable. The
    // sheet's two layouts (signed-out CTA / signed-in domain grid) are
    // driven by its own VM; this surface just controls visibility.
    if (syncSheetOpen) {
        `in`.jphe.storyvox.feature.sync.SyncStatusSheet(
            onDismiss = { syncSheetOpen = false },
            onOpenSignIn = onOpenSync,
            onLearnMore = onOpenSync,
        )
    }
}

/**
 * Issue #517 — brass-edged TechEmpower hero card. The first item in
 * the Library grid on the All filter, pinned above Resume and "Your
 * library". Reads as the lead surface for the TechEmpower
 * default-use-case framing: sun-disk logo, mission tagline, primary
 * CTA into TechEmpower Home + a small Discord chip for one-tap
 * peer-support reachability without leaving Library.
 *
 * 132dp tall — same height as [ResumeCard] so the two hero rows
 * align visually when both are present. The brass border at 2dp is
 * thicker than the 1dp surface-container default to make this card
 * read as the "lead" item without resorting to a saturated
 * background colour (which would clash with Library Nocturne).
 */
@Composable
private fun TechEmpowerHeroCard(onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(2.dp, brass.copy(alpha = 0.60f)),
    ) {
        Row(
            modifier = Modifier.padding(spacing.md).fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Sun-disk logo at 64dp — matches the height of the cover
            // thumb in ResumeCard so the two heroes have the same
            // visual mass.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(
                    id = `in`.jphe.storyvox.ui.R.drawable.techempower_sun,
                ),
                contentDescription = "TechEmpower sun-disk logo",
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "TechEmpower",
                    style = MaterialTheme.typography.labelMedium,
                    color = brass,
                )
                // Issues #603 + #626 (v1.0). The card now leads with a
                // plain-English benefit headline that answers "what
                // does this app do?" in seven words, not the marketing-
                // y MISSION_TAGLINE which made sense only to readers
                // who already knew TechEmpower. The subtitle was
                // previously "Browse free tech guides, call 211, or
                // join the peer-support Discord →" — three CTAs in
                // one tagline, which the auditor flagged for #626. We
                // pick ONE supporting line that points at the lead
                // affordance (Browse the free guides), and let the
                // TechEmpower Home drill-down handle Discord / call
                // 211 with dedicated affordances.
                Text(
                    "Free books, guides, and help read out loud.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                Text(
                    "Tap to see TechEmpower's free guides →",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun ResumeCard(entry: ContinueListeningEntry, onResume: () -> Unit) {
    val spacing = LocalSpacing.current
    val fraction = entry.progressFraction()

    Card(
        modifier = Modifier.fillMaxWidth().height(132.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md).fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            FictionCoverThumb(
                coverUrl = entry.fiction.coverUrl,
                title = entry.fiction.title,
                monogram = fictionMonogram(entry.fiction.author, entry.fiction.title),
                author = entry.fiction.author,
                sourceFamily = coverSourceFamilyFor(entry.fiction.sourceId),
                modifier = Modifier.size(width = 68.dp, height = 100.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Resume", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(entry.fiction.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                // #265 — when chapter.title is blank (RSS feeds where only
                // the index was parsed, first-cold-launch state), the old
                // format produced "Ch. 0 · " with a dangling separator
                // that read as missing data. Drop the separator entirely
                // in that case so the line ends cleanly with "Ch. 0".
                Text(
                    text = if (entry.chapter.title.isNotBlank()) {
                        "Ch. ${entry.chapter.index} · ${entry.chapter.title}"
                    } else {
                        "Ch. ${entry.chapter.index}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (fraction != null) {
                    // BrassProgressBar smooth-animates the fill on resume —
                    // when the user opens Library mid-chapter the bar
                    // visibly settles to current progress instead of
                    // snapping. Same brass palette as the rest of the row.
                    BrassProgressBar(
                        progress = fraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.xxs),
                    )
                    Text(
                        "${(fraction * 100).toInt()}% through chapter ${entry.chapter.index}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BrassButton(
                label = "Resume",
                onClick = onResume,
                variant = BrassButtonVariant.Primary,
            )
        }
    }
}

@Composable
private fun EmptyLibrary() {
    val spacing = LocalSpacing.current
    // Same brass-realm rhythm as FollowsScreen.SignedOutEmpty and the
    // ErrorBlock FullScreen treatment — sigil tile, titleMedium primary
    // headline, bodyMedium body. No CTA: Browse is one tap away in the
    // bottom nav and Add-by-URL is on the FAB above. Empty state's job
    // is to acknowledge and invite, not duplicate navigation.
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSkeletonTile(
            modifier = Modifier.size(width = 160.dp, height = 220.dp),
            shape = MaterialTheme.shapes.medium,
            glyphSize = 80.dp,
        )
        Spacer(Modifier.height(spacing.lg))
        Text(
            "Your library is empty",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Browse Royal Road or paste a fiction URL with the + button to start collecting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Issue #116 — empty state per shelf. The library-wide [EmptyLibrary]
 * doesn't fit here: the user *has* books in their library, they just
 * haven't pinned any to this particular shelf. Same brass-realm rhythm
 * as the rest of the empty-state vocabulary (sigil tile, titleMedium
 * primary headline, bodyMedium body) so it reads as part of the family.
 */
@Composable
private fun EmptyShelf(shelf: Shelf) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSkeletonTile(
            modifier = Modifier.size(width = 160.dp, height = 220.dp),
            shape = MaterialTheme.shapes.medium,
            glyphSize = 80.dp,
        )
        Spacer(Modifier.height(spacing.lg))
        Text(
            "Nothing on the ${shelf.displayName} shelf yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Long-press a book to add it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Estimate progress through the chapter from `charOffset` and `wordCount`.
 * Royal Road averages ~5 chars per word incl. spacing; null if word count is missing.
 */
private fun ContinueListeningEntry.progressFraction(): Float? {
    val words = chapter.wordCount ?: return null
    if (words <= 0) return null
    val estimatedChars = words * 5f
    return (charOffset / estimatedChars).coerceIn(0f, 1f)
}

/**
 * Issue #452 — structural marker for [LibraryGridLandscapeTest]. Set
 * to `true` when `LibraryGridBody` is wrapped in a
 * `Box(Modifier.weight(1f).fillMaxWidth())` inside its parent Column.
 * The regressed pre-#452 shape placed `LazyVerticalGrid` directly as a
 * Column child without a weight wrapper; in landscape orientation
 * (incl. Z Flip3 unfolded) the grid measured to zero height and the
 * library appeared empty even though the books were in the database.
 */
internal const val libraryGridIsWeightBounded: Boolean = true

/**
 * Issue #452 — pinned value of the `GridCells.Adaptive` `minSize` (in
 * dp). The 140dp tuning matches the cover-thumb aspect ratio used
 * elsewhere in the library; changing it shifts the column count on
 * every supported display, so the test pins the value and asks any PR
 * that bumps it to bump the test in lock-step.
 */
internal const val libraryGridAdaptiveMinSizeDp: Int = 140

/**
 * Issue #158 — All / Reading library grid. Extracted from the inlined body
 * so the same composable serves both sub-tabs without duplication. The
 * Resume card + "Your library" caption hero zone is preserved as the
 * first full-span rows in the grid (unchanged from the pre-sub-tabs
 * implementation).
 */
/**
 * The unified library grid body. Renders the chip-aware empty state, the
 * Resume hero (only on filter == All, so it doesn't surface inside a
 * Wishlist scope), and the cascading grid with long-press → manage-shelves.
 *
 * Rendered under [LibraryTab.Library] with the shelf chip row visible
 * above. #438 dropped the old [LibraryTab]`.Reading` shortcut tab; the
 * shelf filter now lives entirely in the chip row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGridBody(
    state: LibraryUiState,
    dedupedFictions: List<FictionSummary>,
    onResume: () -> Unit,
    onOpenFiction: (String) -> Unit,
    onLongPress: (FictionSummary) -> Unit,
    onOpenTechEmpower: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val isEmpty = !state.isLoading && dedupedFictions.isEmpty() && state.resume == null
    if (isEmpty) {
        when (val f = state.filter) {
            ShelfFilter.All -> {
                // Issue #517 — even with an empty library, surface the
                // TechEmpower hero card as the first item so the
                // brand-and-mission framing reads as the lead surface
                // on first-launch / wiped-data states. The empty-state
                // hint about Browse / Add-by-URL still renders below
                // the hero via [EmptyLibrary] — call it after the hero
                // in a Column so both stack.
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.padding(spacing.md)) {
                        TechEmpowerHeroCard(onClick = onOpenTechEmpower)
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        EmptyLibrary()
                    }
                }
            }
            is ShelfFilter.OneShelf -> EmptyShelf(f.shelf)
        }
        return
    }
    LazyVerticalGrid(
        // Issue #452 — see [libraryGridAdaptiveMinSizeDp] for the
        // rationale. Pinned to 140dp; the regression test
        // [LibraryGridLandscapeTest] guards both the value and the
        // weight-bounded parent wrapper.
        columns = GridCells.Adaptive(minSize = libraryGridAdaptiveMinSizeDp.dp),
        contentPadding = PaddingValues(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // Issue #517 — TechEmpower hero card pinned as the FIRST grid
        // item on the All filter. Brass-edged, full-span, larger than
        // a fiction tile — reads as the lead surface for the
        // TechEmpower default-use-case framing without dominating the
        // user's actual library underneath. Only shown on filter ==
        // All; shelf-filtered views are user-scoped and the
        // brand-and-mission frame is the wrong context there.
        if (state.filter is ShelfFilter.All) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                TechEmpowerHeroCard(onClick = onOpenTechEmpower)
            }
        }
        // Hide the Resume card on shelf-filtered views — it's a
        // library-wide affordance, and surfacing it inside a Wishlist
        // filter (a book the user hasn't started) is visually confusing.
        if (state.filter is ShelfFilter.All) {
            state.resume?.let { entry ->
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    ResumeCard(entry, onResume = onResume)
                }
                // #265 — single full-span caption row labelling the grid
                // as a separate section beneath the Resume hero. See the
                // kdoc on the original implementation for the gap-doubling
                // rationale.
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.padding(top = spacing.xs)) {
                        Text(
                            text = "Your library",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = spacing.xxs),
                        )
                    }
                }
            }
        }
        itemsIndexed(dedupedFictions, key = { _, item -> item.id }) { index, fiction ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .cascadeReveal(index = index, key = fiction.id),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                FictionCoverThumb(
                    coverUrl = fiction.coverUrl,
                    title = fiction.title,
                    monogram = fictionMonogram(fiction.author, fiction.title),
                    author = fiction.author,
                    sourceFamily = coverSourceFamilyFor(fiction.sourceId),
                    // Long-press → manage-shelves bottom sheet (#116).
                    // combinedClickable keeps the tap-to-open path
                    // identical to before; long-press is additive.
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpenFiction(fiction.id) },
                            onLongClick = { onLongPress(fiction) },
                        ),
                )
                Text(
                    fiction.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                if (fiction.author.isNotBlank()) {
                    Text(
                        fiction.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Issue #158 — chronological reading-history list. Most-recent open at the
 * top. Each row: cover thumb, fiction title, chapter title, "2h ago"
 * relative timestamp. Tap → reader at that chapter.
 *
 * LazyColumn (not LazyVerticalGrid) because History is a single-column
 * timeline — the visual rhythm is closer to a chat log than a poster
 * wall. Brass-themed cover thumbs match the Library grid aesthetic.
 *
 * Empty state mirrors [EmptyLibrary]: same sigil + heading + invitation
 * pattern, just worded for the no-history case ("Start listening and
 * chapters will show up here.").
 */
@Composable
private fun HistoryList(
    entries: List<HistoryEntry>,
    onOpenChapter: (HistoryEntry) -> Unit,
) {
    val spacing = LocalSpacing.current
    if (entries.isEmpty()) {
        EmptyHistory()
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items(entries, key = { entry -> "${entry.fictionId}::${entry.chapterId}" }) { entry ->
            HistoryRow(entry, onClick = { onOpenChapter(entry) })
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    Card(
        // a11y (#481): Role.Button for the history-row open tap.
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(spacing.sm).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            FictionCoverThumb(
                coverUrl = entry.coverUrl,
                title = entry.fictionTitle.orEmpty(),
                // Fallback monogram if a cascade-delete race left us with
                // a null fictionTitle (see ChapterHistoryRow kdoc). Empty
                // strings here just produce a brass sigil placeholder
                // instead of a "?" — see #322's fictionMonogram.
                monogram = fictionMonogram(entry.fictionAuthor.orEmpty(), entry.fictionTitle.orEmpty()),
                modifier = Modifier.size(width = 44.dp, height = 64.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.fictionTitle ?: "(removed)",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                // Match Resume card formatting: "Ch. N · title" with the
                // dangling-separator guard for blank titles (see #265).
                val chapterLabel = buildString {
                    entry.chapterIndex?.let { append("Ch. $it") }
                    val title = entry.chapterTitle.orEmpty()
                    if (entry.chapterIndex != null && title.isNotBlank()) append(" · ")
                    if (title.isNotBlank()) append(title)
                    if (isEmpty()) append("(chapter removed)")
                }
                Text(
                    text = chapterLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = relativeTimeLabel(entry.openedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EmptyHistory() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSkeletonTile(
            modifier = Modifier.size(width = 160.dp, height = 220.dp),
            shape = MaterialTheme.shapes.medium,
            glyphSize = 80.dp,
        )
        Spacer(Modifier.height(spacing.lg))
        Text(
            "Nothing here yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Start listening and the chapters you open will show up here, newest first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Coarse "how long ago" label for the History list. Bucketed for human
 * scanability rather than precise: "just now" (under a minute), "Nm ago"
 * (under an hour), "Nh ago" (under a day), "Nd ago" (under a week),
 * "Nw ago" otherwise. Past ~12 weeks we still emit "Nw ago" — for a
 * forever-retention surface we'd rather over-emit weeks than introduce a
 * month-or-year boundary that would render dates inconsistently across
 * locales. If JP wants absolute dates we add them as a tooltip later.
 *
 * Uses `System.currentTimeMillis()` so the label is point-in-time-of-
 * composition; Compose recomposes the History list whenever the
 * underlying flow emits (i.e. on every new open). For a forever-running
 * Library screen the labels eventually drift — that's fine, the next
 * compositional kick (tab switch, new open, app foreground) refreshes
 * them. We deliberately don't run a per-second ticker for what's
 * essentially a secondary-priority caption.
 */
/**
 * Issue #453 — format a chapter row's subtitle without leaking
 * storyvox's internal 0-indexed chapter counter into the UI.
 *
 * Three shapes:
 *  - Blank title (RSS feeds where only the index was parsed,
 *    cold-launch state) → `"Ch. {index+1}"` (1-indexed display).
 *  - Title already starts with "Chapter N" / "Ch. N" / "Episode N" /
 *    "Part N" → return the title only. The previous "Ch. 0 · Chapter 1"
 *    shape mixed 0-indexed and 1-indexed counts and read as broken.
 *  - Plain title (Royal Road / AO3 chapter names, etc.) →
 *    `"Ch. {index+1} · {title}"`.
 *
 * Pure function so the same logic runs in Library rows, Resume cards,
 * and History rows without duplicating the indexing rules.
 */
internal fun formatChapterLabel(index: Int, title: String): String {
    val indexedTitlePattern = Regex("(?i)^\\s*(ch(apter|\\.)?|episode|part)\\s*\\d+.*")
    return when {
        title.isBlank() -> "Ch. ${index + 1}"
        title.matches(indexedTitlePattern) -> title
        else -> "Ch. ${index + 1} · $title"
    }
}

private fun relativeTimeLabel(openedAt: Long): String {
    val deltaMs = (System.currentTimeMillis() - openedAt).coerceAtLeast(0L)
    val minutes = deltaMs / 60_000L
    if (minutes < 1L) return "just now"
    if (minutes < 60L) return "${minutes}m ago"
    val hours = minutes / 60L
    if (hours < 24L) return "${hours}h ago"
    val days = hours / 24L
    if (days < 7L) return "${days}d ago"
    val weeks = days / 7L
    return "${weeks}w ago"
}

/**
 * Issue #383 — cross-source Inbox feed. Mirrors [HistoryList] visually
 * (single-column LazyColumn, brass-aware card per row, relative-time
 * stamp) so the user reads the two sub-tabs as members of the same
 * family. Differences:
 *  - Row content is the event's pre-rendered `title` / `body` strings;
 *    no fiction/chapter join (events deliberately survive removal of
 *    the parent rows — see InboxEvent kdoc).
 *  - "Mark all read" action sits above the feed when there's anything
 *    to clear. It's a TextButton, not a top-bar IconButton, because
 *    the destination is "the feed itself" rather than a separate
 *    surface.
 *  - Unread rows render with the surfaceContainerHighest tone vs
 *    surfaceContainerHigh for read rows — a quiet visual cue that
 *    the user has business there.
 */
@Composable
private fun InboxList(
    events: List<InboxEvent>,
    onOpenEvent: (InboxEvent) -> Unit,
    onMarkAllRead: () -> Unit,
) {
    val spacing = LocalSpacing.current
    if (events.isEmpty()) {
        EmptyInbox()
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // "Mark all read" affordance — only visible when there's
        // anything unread, so a fully-read feed doesn't render dead
        // chrome above the rows.
        if (events.any { !it.isRead }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onMarkAllRead) {
                    Text("Mark all read", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = spacing.md,
                end = spacing.md,
                top = spacing.xxs,
                bottom = spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(events, key = { it.id }) { event ->
                InboxRow(event = event, onClick = { onOpenEvent(event) })
            }
        }
    }
}

@Composable
private fun InboxRow(event: InboxEvent, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    Card(
        // a11y (#481): Role.Button for the inbox-row open tap.
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            // Quiet visual cue: unread events sit slightly brighter so
            // a quick scan tells the user where to look.
            containerColor = if (event.isRead) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(spacing.sm).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            // Source-prefix monogram. Same brass-realm sigil family as
            // the fallback monogram on FictionCoverThumb — a single
            // glyph keyed off sourceId. Cheap and consistent even for
            // events whose fiction row has been removed.
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                MagicSkeletonTile(
                    modifier = Modifier.size(width = 44.dp, height = 44.dp),
                    shape = MaterialTheme.shapes.small,
                    glyphSize = 24.dp,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                event.body?.takeIf { it.isNotBlank() }?.let { body ->
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Text(
                        text = event.sourceId.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = relativeTimeLabel(event.ts),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyInbox() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSkeletonTile(
            modifier = Modifier.size(width = 160.dp, height = 220.dp),
            shape = MaterialTheme.shapes.medium,
            glyphSize = 80.dp,
        )
        Spacer(Modifier.height(spacing.lg))
        Text(
            "Nothing in the Inbox yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "New chapters, live programs, and other updates from your sources will land here, newest first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
