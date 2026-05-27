package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Shared scaffold for the 7 dedicated Settings subscreens introduced as
 * the follow-up to #440 (the original hub PR landed in v0.5.38 #467).
 *
 * Each section card on the Settings hub routes to a subscreen that:
 *
 *  - Carries a brass [CenterAlignedTopAppBar] with the section name + a
 *    back-arrow that pops the back stack.
 *  - Hosts the section's row composables in a single vertical-scroll
 *    column, padded `spacing.md` on every edge and `spacing.lg`
 *    between rows — same rhythm as the legacy long-scroll
 *    [SettingsScreen] body so the visual identity stays consistent
 *    when users bounce between the new subscreen graph and the
 *    legacy "All settings" fallback.
 *
 * The component intentionally takes `content` rather than a section-
 * specific row list because the subscreens vary too much in shape
 * (single switch vs. a 7-provider AI form) to share a row primitive.
 *
 * The back-arrow handler is exposed via `internal` visibility so the
 * smoke tests in `:feature` can wire a `mutableStateOf(false)` flag
 * and assert the click path fires without spinning up Compose UI
 * tests (this module ships JVM unit tests only — see [SettingsHubSectionsTest]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubscreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
        content = content,
    )
}

/**
 * Convenience wrapper for the common subscreen body shape: vertical
 * scroll, edge padding, generous inter-card spacing. Subscreens that
 * need a non-scroll body (rare — only Memory Palace currently fits the
 * profile) opt out and embed [SettingsSubscreenScaffold] directly.
 */
@Composable
internal fun SettingsSubscreenBody(
    paddingValues: PaddingValues,
    content: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        content()
    }
}
