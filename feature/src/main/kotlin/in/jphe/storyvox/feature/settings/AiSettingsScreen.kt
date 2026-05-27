package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → AI subscreen (follow-up to #440 / #467).
 *
 * Hosts the cross-provider chat configuration:
 *  - Provider chip strip (Claude / OpenAI / Ollama / Vertex / Foundry
 *    / Bedrock / Teams / Off).
 *  - Per-provider config rows (API key, model, endpoint, SA JSON,
 *    region, etc.) — rendered by the selected provider's
 *    `*ProviderRows` block inside [AiSection].
 *  - "Test connection" button and probe outcome message.
 *  - Chat grounding-level switches (chapter title, current sentence,
 *    entire chapter, entire book so far).
 *  - Carry-memory-across-fictions toggle (#217).
 *  - AI actions / tool-use toggle (#216).
 *  - "Sessions" link to the chat history surface.
 *  - "Reset AI" destructive action.
 *
 * The legacy long-scroll [SettingsScreen] still wraps the same
 * [AiSection] composable inside its AI section card, so users
 * searching from the "All settings" escape hatch see the same form.
 */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    onOpenAiSessions: () -> Unit,
    onOpenTeamsSignIn: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = stringResource(R.string.settings_ai_title), onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                AiSection(
                    ai = s.ai,
                    probeOutcome = state.probeOutcome,
                    onSetProvider = viewModel::setAiProvider,
                    onSetClaudeKey = viewModel::setClaudeApiKey,
                    onSetClaudeModel = viewModel::setClaudeModel,
                    onSetOpenAiKey = viewModel::setOpenAiApiKey,
                    onSetOpenAiModel = viewModel::setOpenAiModel,
                    onSetOllamaBaseUrl = viewModel::setOllamaBaseUrl,
                    onSetOllamaModel = viewModel::setOllamaModel,
                    onSetVertexKey = viewModel::setVertexApiKey,
                    onSetVertexModel = viewModel::setVertexModel,
                    onSetVertexServiceAccountJson = viewModel::setVertexServiceAccountJson,
                    vertexSaError = viewModel.vertexSaError.collectAsStateWithLifecycle().value,
                    onClearVertexSaError = viewModel::clearVertexSaError,
                    onSetFoundryKey = viewModel::setFoundryApiKey,
                    onSetFoundryEndpoint = viewModel::setFoundryEndpoint,
                    onSetFoundryDeployment = viewModel::setFoundryDeployment,
                    onSetFoundryServerless = viewModel::setFoundryServerless,
                    onSetBedrockAccessKey = viewModel::setBedrockAccessKey,
                    onSetBedrockSecretKey = viewModel::setBedrockSecretKey,
                    onSetBedrockRegion = viewModel::setBedrockRegion,
                    onSetBedrockModel = viewModel::setBedrockModel,
                    onSetSendChapterText = viewModel::setSendChapterTextEnabled,
                    onSetChatGroundChapterTitle = viewModel::setChatGroundChapterTitle,
                    onSetChatGroundCurrentSentence = viewModel::setChatGroundCurrentSentence,
                    onSetChatGroundEntireChapter = viewModel::setChatGroundEntireChapter,
                    onSetChatGroundEntireBookSoFar = viewModel::setChatGroundEntireBookSoFar,
                    onSetCarryMemoryAcrossFictions = viewModel::setCarryMemoryAcrossFictions,
                    onSetAiActionsEnabled = viewModel::setAiActionsEnabled,
                    onTestConnection = viewModel::testAiConnection,
                    onClearProbeOutcome = viewModel::clearProbeOutcome,
                    onResetAi = viewModel::resetAiSettings,
                    onOpenTeamsSignIn = onOpenTeamsSignIn,
                    onSignOutTeams = viewModel::signOutTeams,
                )
                SettingsLinkRow(
                    title = stringResource(R.string.settings_ai_sessions_title),
                    subtitle = stringResource(R.string.settings_ai_sessions_subtitle),
                    onClick = onOpenAiSessions,
                )
            }
        }
    }
}
