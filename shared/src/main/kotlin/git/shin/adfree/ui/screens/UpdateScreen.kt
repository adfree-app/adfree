package git.shin.adfree.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import git.shin.adfree.l10n.L10n
import git.shin.adfree.l10n.Strings
import git.shin.adfree.platform.currentPlatformHost
import git.shin.adfree.ui.AdGuardViewModel
import git.shin.adfree.ui.theme.AdGuardDarkBg
import git.shin.adfree.ui.theme.AdGuardGreenPrimary
import git.shin.adfree.ui.theme.AdGuardTextMuted
import git.shin.adfree.ui.theme.AdGuardTextPrimary
import git.shin.adfree.ui.theme.AdGuardTextSecondary
import git.shin.adfree.update.ReleaseFeed
import git.shin.adfree.update.ReleaseNotes
import git.shin.adfree.update.UpdateInfo
import git.shin.adfree.update.UpdateUiState

/**
 * The update screen: what this build is, what is newer, and what the newer one
 * says about itself.
 *
 * AdGuard gives updates a page of their own instead of a row in Settings, and
 * the reason is the changelog: a release note is paragraphs long, and the row
 * that has to fit a switch and a version number cannot show it. The row in
 * Settings still exists — it is the door, and it still says whether anything is
 * waiting.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
fun UpdateScreen(
    viewModel: AdGuardViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.appUpdateState.collectAsState()
    val platform = currentPlatformHost()
    // The core names itself with the adblock engine beside it — `1.26.0 +
    // adblock 0.13.3` — and the number a reader wants is the first word of that.
    val version = platform.appVersion().substringBefore('+').trim()
    val uri = LocalUriHandler.current

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AdGuardDarkBg),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = AdGuardTextPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = L10n.get(Strings.settings_update_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AdGuardTextPrimary,
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            VersionCard(
                version = version,
                state = state,
                onCheck = { viewModel.checkAppUpdateManually() },
                onInstall = { viewModel.installAppUpdate(it) },
            )

            val info =
                (state as? UpdateUiState.Available)?.info
                    ?: (state as? UpdateUiState.Downloading)?.info

            Spacer(modifier = Modifier.height(18.dp))

            SectionLabel(L10n.get(Strings.update_notes_title))

            Spacer(modifier = Modifier.height(8.dp))

            // The release body is the changelog for that version: every commit
            // semantic-release wrote into it, in the words it wrote them — and
            // written in Markdown, which is why it is flattened first.
            val notes = info?.releaseNotes.orEmpty()
            if (notes.isBlank()) {
                Text(
                    text =
                        if (info == null) {
                            L10n.get(Strings.update_notes_none_current)
                        } else {
                            L10n.get(Strings.update_notes_empty)
                        },
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = AdGuardTextMuted,
                )
            } else {
                NotesList(ReleaseNotes.lines(notes))
            }

            Spacer(modifier = Modifier.height(18.dp))

            OutlinedButton(
                onClick = { runCatching { uri.openUri(ReleaseFeed.RELEASES_PAGE) } },
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(L10n.get(Strings.update_release_page))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun VersionCard(
    version: String,
    state: UpdateUiState,
    onCheck: () -> Unit,
    onInstall: (UpdateInfo) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AdGuardDarkBg.copy(alpha = 0.6f))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "AdFree",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = AdGuardTextPrimary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (version.isNotBlank()) {
                Text(
                    text = L10n.format(Strings.update_current_version, version),
                    fontSize = 12.sp,
                    color = AdGuardTextMuted,
                )
            }
        }

        Text(
            text = statusLine(state),
            fontSize = 13.sp,
            color = if (state is UpdateUiState.Available) AdGuardGreenPrimary else AdGuardTextSecondary,
        )

        if (state is UpdateUiState.Downloading) {
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                UpdateUiState.Idle -> {
                    Button(onClick = onCheck) {
                        Text(L10n.get(Strings.settings_update_check))
                    }
                }

                UpdateUiState.Checking -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }

                is UpdateUiState.Available -> {
                    Button(onClick = { onInstall(state.info) }) {
                        Text(L10n.get(Strings.update_install))
                    }
                }

                is UpdateUiState.Downloading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            if (state is UpdateUiState.Available) {
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedButton(onClick = onCheck) {
                    Text(L10n.get(Strings.update_check_again))
                }
            }
        }
    }
}

private fun statusLine(state: UpdateUiState): String =
    when (state) {
        // Idle is "nothing was asked yet", not "nothing newer exists": the
        // automatic check follows the filter schedule and may not have run.
        UpdateUiState.Idle -> {
            L10n.get(Strings.settings_update_idle)
        }

        UpdateUiState.Checking -> {
            L10n.get(Strings.settings_update_checking)
        }

        is UpdateUiState.Available -> {
            L10n.format(Strings.settings_update_available, state.info.version)
        }

        is UpdateUiState.Downloading -> {
            L10n.format(Strings.settings_update_downloading, state.progress)
        }
    }

@Composable
@Suppress("ktlint:standard:function-naming")
private fun NotesList(lines: List<ReleaseNotes.Line>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { line ->
            when (line.kind) {
                ReleaseNotes.Kind.HEADING -> {
                    Text(
                        text = line.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AdGuardTextPrimary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                ReleaseNotes.Kind.BULLET -> {
                    Row {
                        Text("•", fontSize = 12.sp, color = AdGuardTextMuted)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(line.text, fontSize = 12.sp, lineHeight = 17.sp, color = AdGuardTextPrimary)
                    }
                }

                ReleaseNotes.Kind.TEXT -> {
                    Text(
                        text = line.text,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = AdGuardTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun SectionLabel(text: String) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = AdGuardTextSecondary,
        )
    }
}
