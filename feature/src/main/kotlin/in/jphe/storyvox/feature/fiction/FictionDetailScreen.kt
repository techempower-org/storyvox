package `in`.jphe.storyvox.feature.fiction

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.source.epub.writer.EpubExportResult
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.ChapterCard
import `in`.jphe.storyvox.ui.component.ChapterCardState
import `in`.jphe.storyvox.ui.component.MagicCircularProgress
import `in`.jphe.storyvox.ui.component.coverSourceFamilyFor
import `in`.jphe.storyvox.ui.component.ErrorBlock
import `in`.jphe.storyvox.ui.component.ErrorPlacement
import `in`.jphe.storyvox.ui.component.friendlyErrorMessage
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.component.FictionDetailSkeleton
import `in`.jphe.storyvox.ui.layout.isAtLeastTablet
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FictionDetailScreen(
    onOpenReader: (String, String) -> Unit,
    /** Issue #169 — the no-cache full-page error path was a dead-end
     *  with no nav (no Back, no Retry, only OS back). AppNav wires
     *  this so the user always has a way out. Default `{}` keeps
     *  preview/test use working but should never be the production
     *  callsite. */
    onBack: () -> Unit = {},
    /** Issue #211 — routes the Royal Road sign-in flow when the user
     *  taps Follow on RR without an active session. Same destination
     *  as Settings → Royal Road and the Browse anonymous-CTA from
     *  #241. */
    onOpenRoyalRoadSignIn: () -> Unit = {},
    viewModel: FictionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val synopsisSpeaking by viewModel.synopsisSpeaking.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val twoColumn = isAtLeastTablet()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Issue #117 — Export-result handling. When the use case finishes,
    // we surface the share / save sheet here. The pending result is
    // held in screen state so the share sheet can re-render on config
    // change without re-firing the export.
    var pendingExport by remember { mutableStateOf<EpubExportResult?>(null) }

    // SAF "Save…" picker. Wired up as a launcher up-front so the user
    // can also kick it off from the sheet without us having to remember
    // whether we already initialized it. The contract delivers the
    // user-chosen target URI; we copy the cache file into it.
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { destination ->
        val source = pendingExport
        if (destination != null && source != null) {
            // Best-effort copy from cache → SAF target. Failures get a
            // snackbar; the cache file is left in place so the share
            // path is still usable as a fallback.
            try {
                context.contentResolver.openOutputStream(destination)?.use { out ->
                    source.file.inputStream().use { it.copyTo(out) }
                }
            } catch (t: Throwable) {
                // Show a snackbar via the surrounding scope. We can't
                // suspend inside this lambda, so launch into a SideEffect.
                pendingExport = source.copy(
                    warnings = source.warnings + "Save failed: ${t.message ?: t.javaClass.simpleName}",
                )
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FictionDetailUiEvent.OpenReader -> onOpenReader(event.fictionId, event.chapterId)
                is FictionDetailUiEvent.EpubExported -> pendingExport = event.result
                is FictionDetailUiEvent.EpubExportFailed ->
                    snackbarHostState.showSnackbar(event.message)
                FictionDetailUiEvent.OpenRoyalRoadSignIn -> onOpenRoyalRoadSignIn()
                is FictionDetailUiEvent.FollowFailed ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // Issue #169 — destructive removeFromLibrary used to fire on a
    // single tap of the "In library" button (which reads as a status,
    // not an action). User lost their fiction + read progress with
    // zero confirmation. Gate the destructive path behind an
    // AlertDialog; the additive add-to-library path stays single-tap.
    var showRemoveConfirm by remember { mutableStateOf(false) }

    // PR-H (#86) — destructive "Clear fiction cache" confirm gate.
    // Mirrors showRemoveConfirm's pattern: ephemeral state held at the
    // screen-composable level so the dialog dismisses cleanly on
    // configuration change without surviving as a half-rendered modal.
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    if (showClearCacheConfirm) {
        val titleForDialog = state.fiction?.title ?: stringResource(R.string.fiction_default_title)
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text(stringResource(R.string.fiction_clear_cache_title, titleForDialog)) },
            text = {
                Text(stringResource(R.string.fiction_clear_cache_body))
            },
            confirmButton = {
                BrassButton(
                    label = stringResource(R.string.fiction_clear),
                    onClick = {
                        showClearCacheConfirm = false
                        viewModel.clearFictionCache()
                    },
                    // No Destructive variant in BrassButton yet — Primary
                    // is the strongest affordance the design system ships
                    // and matches the existing "Remove from library"
                    // destructive confirm above (also Primary).
                    variant = BrassButtonVariant.Primary,
                )
            },
            dismissButton = {
                BrassButton(
                    label = stringResource(R.string.fiction_cancel),
                    onClick = { showClearCacheConfirm = false },
                    variant = BrassButtonVariant.Secondary,
                )
            },
        )
    }
    if (showRemoveConfirm) {
        val titleForDialog = state.fiction?.title ?: stringResource(R.string.fiction_default_title)
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(R.string.fiction_remove_title, titleForDialog)) },
            text = {
                Text(stringResource(R.string.fiction_remove_body))
            },
            confirmButton = {
                BrassButton(
                    label = stringResource(R.string.fiction_remove),
                    onClick = {
                        showRemoveConfirm = false
                        viewModel.toggleFollow(false)
                    },
                    variant = BrassButtonVariant.Primary,
                )
            },
            dismissButton = {
                BrassButton(
                    label = stringResource(R.string.fiction_cancel),
                    onClick = { showRemoveConfirm = false },
                    variant = BrassButtonVariant.Secondary,
                )
            },
        )
    }

    val fiction = state.fiction
    // Issue #257 — Fiction detail used to fade from the system status bar
    // straight into the cover image with no app bar — no back arrow, no
    // title, no place for overflow actions (Refresh, Mark all read, etc).
    // Wrap in a Scaffold + TopAppBar so back-navigation has a visible
    // affordance (in addition to OS gesture) and the fiction title sits
    // at the top of the screen as standard for a detail surface. The
    // existing Hero composable still renders the bigger title; the bar
    // copy is intentionally compact so the dual rendering reads as
    // 'context + content' not 'duplicate'.
    Scaffold(
        topBar = {
            // Issue #117 — overflow menu now houses the EPUB export entry.
            // Held local-to-the-bar so the menu state doesn't survive
            // configuration change (parent screen state is what actually
            // matters; menu open/closed is ephemeral UI affordance).
            var menuOpen by remember { mutableStateOf(false) }
            TopAppBar(
                title = {
                    Text(
                        text = fiction?.title ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    // Gate behind a non-null fiction — there's nothing to
                    // export until the detail row exists. The skeleton
                    // / first-load error states render no overflow.
                    if (fiction != null) {
                        if (state.isExportingEpub) {
                            // Inline progress chip replaces the More icon
                            // while the export runs — gives the user a
                            // visible "your tap took effect" indicator on
                            // long exports. The menu is unreachable during
                            // export by design; no need to re-open it.
                            // a11y (#484): tag the spinner+label row as
                            // a polite live region so TalkBack announces
                            // "Loading: Building .epub" when the export
                            // kicks off and silently re-announces when
                            // the text changes. Without this the spinner
                            // is silent — user can't tell the long
                            // export is actually running.
                            Row(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "Loading: Building .epub"
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // v1.0 polish (2026-05-16) — swap the
                                // Material CircularProgressIndicator for
                                // MagicCircularProgress so the .epub
                                // export progress reads as the same
                                // brass-sigil family used everywhere
                                // else in the app. Tiny size (16 dp) so
                                // the inner star + outer dashed ring
                                // collapses to a recognizable brass dot
                                // with a slow ring sweep — still distinct
                                // from a static glyph, still ours.
                                MagicCircularProgress(
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(stringResource(R.string.fiction_building_epub), style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.fiction_export_epub)) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.exportToEpub(context)
                                    },
                                )
                                // PR-H (#86) — destructive cache wipe for
                                // this fiction. Routes through a confirm
                                // dialog (showClearCacheConfirm flag) so a
                                // mistap doesn't immediately delete every
                                // chapter's cached audio across every
                                // voice variant. Always visible — a no-op
                                // wipe on an already-empty cache is cheap
                                // and lets the user dismiss the dialog
                                // without surprise. Gating on
                                // "any chapter has cacheState != None"
                                // is possible but the inspector flow may
                                // not have first-emitted yet when the
                                // user opens the menu on a cold launch,
                                // so the gate would flicker.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.fiction_clear_cache)) },
                                    onClick = {
                                        menuOpen = false
                                        showClearCacheConfirm = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
        if (fiction == null && state.error != null) {
            // First-load failure with no cached fiction. Issue #169 wired
            // onBack so the user always has a way out without leaning on
            // the OS gesture. Issue #806 added the Retry CTA — re-fires
            // the same refreshDetail the screen ran on first subscription
            // (was a dead-end requiring Back + re-tap).
            ErrorBlock(
                title = "Couldn't load this fiction",
                message = friendlyErrorMessage(state.error),
                onRetry = viewModel::retryRefresh,
                onBack = onBack,
                placement = ErrorPlacement.FullScreen,
            )
        } else if (fiction == null) {
            FictionDetailSkeleton(modifier = Modifier.fillMaxSize())
        } else if (twoColumn) {
            // Wide layout: cover + meta + synopsis on the left, scrollable chapter list
            // on the right. Action row (Play / library / follow) sits as the FIRST item
            // in the chapter LazyColumn — see issue #638 for why the floating bottom
            // bar was removed (it overlapped the bottom-dock hit area on phone; we
            // moved both layouts onto the same in-content shape for consistency).
            Row(
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (state.error != null) {
                        ErrorBlock(
                            title = "Couldn't refresh",
                            message = friendlyErrorMessage(state.error),
                            onRetry = viewModel::retryRefresh,
                            placement = ErrorPlacement.Banner,
                        )
                    }
                    Hero(fiction)
                    Synopsis(
                        text = fiction.synopsis,
                        speakingState = synopsisSpeaking,
                        onToggleListen = viewModel::toggleSynopsisAloud,
                    )
                    // Issue #217 — Notebook section in the wide-layout
                    // left column. Hides itself if there's nothing to show
                    // and the user hasn't tapped Add.
                    NotebookSection(
                        entries = state.notebookEntries,
                        onDelete = viewModel::deleteNotebookEntry,
                        onAdd = viewModel::addNotebookEntry,
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(0.58f).fillMaxSize(),
                    contentPadding = PaddingValues(top = spacing.md, bottom = spacing.md),
                ) {
                    // Issue #638 — action row pinned at the top of the
                    // chapter list. Was a floating BottomBar; moved
                    // in-content because the floating placement overlapped
                    // the parent Scaffold's bottom-dock hit-area on phone
                    // and intercepted Play taps as back-nav to Library.
                    item {
                        ActionRow(
                            isInLibrary = state.isInLibrary,
                            followOnSource = fiction.takeIf { it.sourceSupportsFollow }
                                ?.let { FollowOnSourceUiState(isFollowed = it.isFollowedRemote) },
                            onFollow = {
                                if (state.isInLibrary) {
                                    showRemoveConfirm = true
                                } else {
                                    viewModel.toggleFollow(true)
                                }
                            },
                            onFollowOnSource = viewModel::toggleFollowOnSource,
                            onPlay = pickChapterToPlay(state.chapters)?.let { picked ->
                                { viewModel.listen(picked.id) }
                            },
                            playLabel = playButtonLabel(
                                state.chapters,
                                pickChapterToPlay(state.chapters),
                            ),
                        )
                    }
                    items(state.chapters, key = { it.id }) { ch ->
                        ChapterCard(
                            state = ch.toCardState(currentId = null),
                            onClick = { viewModel.listen(ch.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.md, vertical = spacing.xxs),
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = spacing.md),
            ) {
                if (state.error != null) {
                    item {
                        ErrorBlock(
                            title = "Couldn't refresh",
                            message = friendlyErrorMessage(state.error),
                            onRetry = viewModel::retryRefresh,
                            placement = ErrorPlacement.Banner,
                        )
                    }
                }
                item { Hero(fiction) }
                // Issue #638 (v1.0 blocker) — action row moved out of a
                // floating BottomBar and into the LazyColumn just above
                // the synopsis. Pre-fix, the BottomBar floated at
                // Alignment.BottomCenter inside the FictionDetail Box
                // with bounds [48,2359][522,2503] on a 1080×2640 phone.
                // The parent navigation Scaffold's bottom-dock (Library /
                // Playing / Voices / Settings) lives at y=2355-2595, and
                // even on FictionDetail (which deliberately hides the
                // dock per StoryvoxRoutes.showsBottomNav returning false
                // for `fiction/{fictionId}`) the bottom-edge gesture area
                // intercepted Play taps and routed the user back to the
                // Library tab's "Your library awaits" empty state instead
                // of starting playback. Reproduced on R5CRB0W66MK
                // (Z Flip3 unfolded). Moving the row into the scroll
                // body eliminates the overlap by construction — no insets
                // math, no floating element competing with the dock /
                // gesture surface.
                item {
                    ActionRow(
                        isInLibrary = state.isInLibrary,
                        followOnSource = fiction.takeIf { it.sourceSupportsFollow }
                            ?.let { FollowOnSourceUiState(isFollowed = it.isFollowedRemote) },
                        onFollow = {
                            // Issue #169 — gate the destructive path
                            // behind a confirm dialog; the additive
                            // path stays single-tap.
                            if (state.isInLibrary) {
                                showRemoveConfirm = true
                            } else {
                                viewModel.toggleFollow(true)
                            }
                        },
                        onFollowOnSource = viewModel::toggleFollowOnSource,
                        // Issue #604 — Play CTA. First non-finished
                        // chapter (resume) or fall back to chapter 1.
                        onPlay = pickChapterToPlay(state.chapters)?.let { picked ->
                            { viewModel.listen(picked.id) }
                        },
                        playLabel = playButtonLabel(
                            state.chapters,
                            pickChapterToPlay(state.chapters),
                        ),
                    )
                }
                item {
                    Synopsis(
                        text = fiction.synopsis,
                        speakingState = synopsisSpeaking,
                        onToggleListen = viewModel::toggleSynopsisAloud,
                    )
                }
                // Issue #217 — Notebook section in the narrow (phone)
                // layout. Inserted as a single LazyColumn item so it
                // scrolls with the chapter list rather than pinning.
                item {
                    NotebookSection(
                        entries = state.notebookEntries,
                        onDelete = viewModel::deleteNotebookEntry,
                        onAdd = viewModel::addNotebookEntry,
                    )
                }
                items(state.chapters, key = { it.id }) { ch ->
                    ChapterCard(
                        state = ch.toCardState(currentId = null),
                        onClick = { viewModel.listen(ch.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.md, vertical = spacing.xxs),
                    )
                }
            }
        }
    }
    }

    // Issue #117 — Share / Save-as bottom sheet. Renders only after the
    // export use case completes and posts an EpubExported event. JP's
    // decision: ACTION_SEND is the primary path (user picks the destination
    // app: Drive / email / file manager / etc); ACTION_CREATE_DOCUMENT is
    // the secondary "Save…" path for users who want explicit filesystem
    // placement. Both routes go through the same FileProvider-backed Uri.
    pendingExport?.let { exported ->
        ExportSheet(
            export = exported,
            onShare = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/epub+zip"
                    putExtra(Intent.EXTRA_STREAM, exported.uri)
                    putExtra(Intent.EXTRA_TITLE, exported.suggestedFileName)
                    // Grant the chosen target read access to our FileProvider
                    // URI. Without this flag, every recipient app gets a
                    // SecurityException on the first read.
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(send, "Share .epub").also {
                        // Chooser itself runs in a new task when launched
                        // from a non-activity Context; the LocalContext
                        // here is the activity, but flagging it cheap
                        // and avoids a crash if the surrounding host
                        // ever switches.
                        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
                pendingExport = null
            },
            onSaveAs = {
                // Pre-fill the SAF picker with the suggested name —
                // ActivityResultContracts.CreateDocument takes the
                // input directly (it's the proposed file name).
                saveAsLauncher.launch(exported.suggestedFileName)
                pendingExport = null
            },
            onDismiss = { pendingExport = null },
        )
    }
}

/**
 * Issue #117 — modal bottom sheet that surfaces after the EPUB export
 * completes. Offers Share (primary, fires ACTION_SEND chooser) and
 * Save… (secondary, fires SAF CREATE_DOCUMENT). Both routes use the
 * same FileProvider-backed Uri held on [EpubExportResult].
 *
 * Warnings (cover failed to fetch, N chapters not downloaded) render
 * inline above the action buttons so the user knows what they're
 * actually shipping out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    export: EpubExportResult,
    onShare: () -> Unit,
    onSaveAs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = "Your .epub is ready",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = export.suggestedFileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Surface any non-fatal issues — cover download failures,
            // chapters not downloaded — so the user knows the export
            // isn't lossless on the cases where it isn't.
            if (export.warnings.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    export.warnings.forEach { w ->
                        Text(
                            text = "• $w",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(spacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                BrassButton(
                    label = "Save…",
                    onClick = onSaveAs,
                    variant = BrassButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                BrassButton(
                    label = "Share",
                    onClick = onShare,
                    variant = BrassButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(fiction: UiFiction) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        FictionCoverThumb(
            coverUrl = fiction.coverUrl,
            title = fiction.title,
            monogram = fictionMonogram(fiction.author, fiction.title),
            author = fiction.author,
            sourceFamily = coverSourceFamilyFor(fiction.sourceId),
            modifier = Modifier.size(width = 120.dp, height = 180.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(fiction.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            // Issue #463 — without an explicit "by" prefix, the author
            // line read as a subtitle of the title (especially for
            // GitHub fictions whose book.toml authors[] can carry an
            // ambiguous string like a repo-name-shaped label). Adding
            // "by" anchors the byline role unambiguously, matching the
            // pattern used on Library Resume cards and Browse listing.
            //
            // Issue #657 — on Tab A7 Lite portrait (800px) with a 120dp
            // cover thumbnail eating the left half of the hero, a long
            // author name like "by TechEmpower" wrapped to two lines
            // (113×76px). That pushed the metadata row down, and the
            // last metadata pill ("Ongoing") was clipped or dropped by
            // the parent Row. Cap the byline at one line with ellipsis
            // — readers care more about seeing the status badge than
            // seeing the full author name (which is also visible above
            // the fold elsewhere in the surface).
            if (fiction.author.isNotBlank()) {
                Text(
                    "by ${fiction.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(spacing.xxs))
            // Issue #657 — FlowRow (rather than Row) so the metadata
            // pills wrap onto a second line when the narrow column
            // can't fit `rating · chapters · status` on one line.
            // Plain Row clipped the trailing "Ongoing" pill silently
            // on Tab A7 Lite portrait; FlowRow keeps every pill
            // visible at every width, and on wider devices the
            // single-line layout is unchanged because no wrap is
            // triggered.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text("%.1f".format(fiction.rating), style = MaterialTheme.typography.labelMedium)
                }
                Text(stringResource(R.string.fiction_separator), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.fiction_chapter_count, fiction.chapterCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.fiction_separator), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (fiction.isOngoing) "Ongoing" else "Completed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Issue #450 — structural marker for [NotebookFocusContractTest]. Set
 * to `true` when the per-fiction Notebook "Add note" dialog wires
 * explicit `FocusRequester`s + `ImeAction.Next` on the first field so
 * that taps on the second field always shift focus there (and the
 * keyboard's Next key works as a fallback). The regressed shape from
 * v0.5.36 used bare `OutlinedTextField`s with no IME action and no
 * focus requesters; on the Z Flip3 with the IME up, taps on the
 * second field were consumed by the first field's
 * `bringIntoViewRequester` and typed text concatenated into the wrong
 * field.
 */
internal const val notebookAddNoteUsesExplicitFocus: Boolean = true

/**
 * Issue #217 — per-fiction Notebook section. Renders the AI-extracted
 * + user-curated entities the cross-fiction memory layer has recorded
 * for this book. Empty list collapses the section entirely; the
 * surface only materialises once there's something to show OR once
 * the user expands the "Add note" affordance.
 *
 * Each row shows the entity name, its kind (CHARACTER/PLACE/CONCEPT),
 * and the one-line summary. A small "X" affordance per row lets the
 * user delete AI-extracted entries they judge wrong; the same path
 * works for user-edited notes ("retract this manual note"). The
 * "Add note" expander opens a tiny form (name + summary + kind chips)
 * that calls [onAdd] with the user's input.
 *
 * Per the v1 trade-offs in #217: this is a flat list, not a tabbed
 * surface; the issue calls for a sub-tab but a section header inside
 * the existing scroll reads cleaner on phones (no tab navigation
 * overhead, no double-scroll) and keeps the destructive delete close
 * to the chapter list user already has muscle memory for.
 */
@Composable
private fun NotebookSection(
    entries: List<`in`.jphe.storyvox.data.db.entity.FictionMemoryEntry>,
    onDelete: (String) -> Unit,
    onAdd: (String, String, `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry.Kind) -> Unit,
) {
    val spacing = LocalSpacing.current
    // Expander state for the manual-add affordance. Held local-to-the-
    // section because it's pure UI affordance — survives recomposition
    // but doesn't need to survive process death (the form is empty
    // until the user types, and a discard is a single tap).
    var addOpen by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var draftSummary by remember { mutableStateOf("") }
    var draftKind by remember {
        mutableStateOf(`in`.jphe.storyvox.data.db.entity.FictionMemoryEntry.Kind.CHARACTER)
    }
    // Issue #450 — explicit focus plumbing for the two-field "Add note"
    // form. Without these, a tap on the Description field while the
    // Name field has focus + IME-up would route the keypress to the
    // first field (Compose's `bringIntoViewRequester` was winning the
    // pointer-event race in this scrolled-inside-LazyColumn context).
    // Wiring `imeAction = Next` + `KeyboardActions(onNext)` gives users
    // a soft-keyboard path to move between fields, and a dedicated
    // FocusRequester per field lets the field-tap hit-tester reliably
    // hand focus to whichever field the user actually tapped.
    val nameFocus = remember { FocusRequester() }
    val summaryFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Issue #624 (v1.0). When the user has no notes yet AND the add
    // sheet isn't open, the entire section hides — header, empty-state
    // copy, "Add note" button, the lot. A first-launch user landing on
    // a FictionDetail shouldn't see a Notebook section that points at a
    // workflow they haven't started; characters and places appear
    // automatically as they listen (AI-driven extraction in
    // :core-playback fills [entries]). Once the first entry lands —
    // either AI-extracted or via the long-press "save to notebook"
    // path on the reader surface — the section appears with its
    // header + the entry, and the "Add note" affordance becomes
    // available for editing.
    //
    // Kept as an early-return rather than a wrapping `if` so the
    // composable's whole body remains one indent level — easier to
    // diff against future surface changes.
    if (entries.isEmpty() && !addOpen) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Notebook",
                style = MaterialTheme.typography.titleMedium,
            )
            BrassButton(
                label = if (addOpen) "Cancel" else "Add note",
                onClick = {
                    addOpen = !addOpen
                    if (!addOpen) {
                        draftName = ""
                        draftSummary = ""
                    }
                },
                variant = BrassButtonVariant.Text,
            )
        }
        entries.forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Text(
                            e.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            e.entityType.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (e.userEdited) {
                            Text(
                                "manual",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        e.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BrassButton(
                    label = "Remove",
                    onClick = { onDelete(e.name) },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        if (addOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                androidx.compose.material3.OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text(stringResource(R.string.fiction_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = draftSummary,
                    onValueChange = { draftSummary = it },
                    label = { Text(stringResource(R.string.fiction_one_line_description)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(summaryFocus),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry.Kind.entries.forEach { k ->
                        BrassButton(
                            label = k.name.lowercase().replaceFirstChar { it.uppercase() },
                            onClick = { draftKind = k },
                            variant = if (draftKind == k) BrassButtonVariant.Primary
                                else BrassButtonVariant.Secondary,
                        )
                    }
                }
                BrassButton(
                    label = "Save note",
                    onClick = {
                        if (draftName.isNotBlank() && draftSummary.isNotBlank()) {
                            onAdd(draftName.trim(), draftSummary.trim(), draftKind)
                            draftName = ""
                            draftSummary = ""
                            addOpen = false
                        }
                    },
                    variant = BrassButtonVariant.Primary,
                )
            }
        }
    }
}

/** Issue #760 — Synopsis section with a "Listen to summary" toggle.
 *  The listen icon speaks the FULL synopsis text (not the truncated
 *  4-line preview) via the recap-aloud TTS pipeline. Tapping while
 *  speaking stops playback. The icon tints brass-primary when active
 *  so the user can see at a glance that TTS is running. */
@Composable
private fun Synopsis(
    text: String,
    speakingState: UiRecapPlaybackState = UiRecapPlaybackState.Idle,
    onToggleListen: () -> Unit = {},
) {
    if (text.isBlank()) return
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    val isSpeaking = speakingState == UiRecapPlaybackState.Speaking
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            BrassButton(
                label = if (expanded) "Show less" else "Read more",
                onClick = { expanded = !expanded },
                variant = BrassButtonVariant.Text,
            )
            IconButton(
                onClick = onToggleListen,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (isSpeaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isSpeaking) R.string.fiction_stop_summary
                        else R.string.fiction_listen_summary,
                    ),
                    tint = if (isSpeaking) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Issue #638 (v1.0 blocker) — formerly `BottomBar`, an
 * `Alignment.BottomCenter`-floated Surface. The float collided with the
 * bottom-edge dock + gesture region on phone: the Play button bounds
 * `[48,2359][522,2503]` on a 1080×2640 Z Flip3 sat directly inside the
 * `y=2355–2595` dock band, and the dock was winning the touch-target
 * race even though `StoryvoxRoutes.showsBottomNav("fiction/{fictionId}")`
 * is false. Result: every Play tap bounced the user back to the Library
 * "Your library awaits" empty state, defeating the #633 discoverability
 * fix. The bar is now rendered as a normal in-content row inside the
 * FictionDetail scroll surface — the floating Surface + shadowElevation
 * came off because the row no longer needs to read as "this is on a
 * separate layer than the body". The kept brass styling on the Play
 * button (BrassButtonVariant.Primary) is enough to keep it the row's
 * most prominent affordance without a floating-bar metaphor.
 *
 * When future work needs to restore a sticky bottom CTA, it should
 * route through the parent Scaffold's `bottomBar` slot (or a sibling
 * navigation surface), not a child-Box-aligned float — the latter
 * shape is what created this overlap.
 */
@Composable
private fun ActionRow(
    isInLibrary: Boolean,
    /** Issue #211 — non-null only for Royal Road fictions. When set,
     *  surfaces an inline "Follow" / "Following" toggle next to the
     *  library button so users can push a follow to RR without
     *  leaving the page. The label reads `Following` when storyvox
     *  has observed the user already follows on RR. */
    followOnSource: FollowOnSourceUiState? = null,
    onFollow: () -> Unit,
    onFollowOnSource: () -> Unit = {},
    /** Issue #604 (v1.0 blocker) — Play button restored on FictionDetail.
     *  Pre-#538 a "Listen" button lived here and was removed because it
     *  duplicated chapter-row taps. Post-audit the discoverability cost
     *  was worse than the duplication: cold-launch users with a book in
     *  hand had no visible primary CTA on the detail surface. Issue #638
     *  then moved this row out of a floating BottomBar (which collided
     *  with the bottom-dock hit area) and into the scroll body; the
     *  button is still the row's primary affordance (brass-bordered,
     *  leading slot), with library / follow-on-source still present
     *  as secondary affordances. Null disables the slot — used when no
     *  chapter is resolvable (loading state, parse failure). */
    onPlay: (() -> Unit)? = null,
    /** Issue #604 — label switches between "Play" and "Resume" depending
     *  on whether the fiction has any chapter the user already started.
     *  Drives the TalkBack onClickLabel too. */
    playLabel: String = "Play",
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPlay != null) {
            BrassButton(
                label = playLabel,
                onClick = onPlay,
                // Brass-bordered Primary — the row's most visually
                // prominent affordance. The user-tap-test
                // (R5CRB0W66MK, 2026-05-15) confirmed cold-launch
                // users tap the leading button first; putting Play
                // there means the first tap reaches audio.
                variant = BrassButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        }
        BrassButton(
            label = if (isInLibrary) "In library" else "Add to library",
            onClick = onFollow,
            // Pre-#604 Add-to-library was the row's Primary slot;
            // demoted to Secondary now that Play occupies primary
            // and library state is a lower-frequency action.
            variant = BrassButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
        if (followOnSource != null) {
            BrassButton(
                label = if (followOnSource.isFollowed) "Following" else "Follow",
                onClick = onFollowOnSource,
                variant = BrassButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Issue #604 — pick the chapter the Play button should open.
 *  Strategy: first non-finished chapter, fall back to first chapter.
 *  Returns null when there are no chapters to play (loading state,
 *  empty fiction). Exposed `internal` so FictionDetailScreenTest can
 *  pin the contract without rendering the whole composable.
 */
internal fun pickChapterToPlay(chapters: List<UiChapter>): UiChapter? {
    if (chapters.isEmpty()) return null
    return chapters.firstOrNull { !it.isFinished } ?: chapters.first()
}

/** Issue #604 — derive the Play button label. Reads "Resume" when the
 *  picked chapter isn't the first one (user has finished earlier
 *  chapters); reads "Play" on first-listen or a fully-finished fiction
 *  where pickChapterToPlay falls back to chapter 1. */
internal fun playButtonLabel(chapters: List<UiChapter>, picked: UiChapter?): String {
    if (picked == null) return "Play"
    val firstId = chapters.firstOrNull()?.id
    return if (firstId != null && picked.id != firstId) "Resume" else "Play"
}

/** Issue #211 — payload for the source-side follow chip. Held as a
 *  small struct rather than separate booleans so the BottomBar
 *  composable's signature stays compact; the screen passes null when
 *  the fiction isn't from a follow-aware source. */
private data class FollowOnSourceUiState(
    val isFollowed: Boolean,
)

private fun UiChapter.toCardState(currentId: String?) = ChapterCardState(
    number = number,
    title = title,
    publishedRelative = publishedRelative,
    durationLabel = durationLabel,
    isDownloaded = isDownloaded,
    isFinished = isFinished,
    isCurrent = id == currentId,
    // PR-H (#86) — forward the per-chapter PCM cache state from the
    // view-model's CacheStateInspector flow into the card. `UiChapter.
    // cacheState` defaults to None when the inspector hasn't produced
    // a value (no active voice yet, or first composition before the
    // flow's first emission), so the badge silently no-ops in that
    // window instead of flickering bogus state.
    cacheState = cacheState,
)
