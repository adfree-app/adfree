package git.shin.adfree.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import git.shin.adfree.core.CaptureRecord
import git.shin.adfree.core.coreCaptureClear
import git.shin.adfree.core.coreCaptureRecords
import git.shin.adfree.core.coreCaptureSetEnabled
import git.shin.adfree.l10n.L10n
import git.shin.adfree.l10n.Strings
import kotlinx.coroutines.delay

/**
 * What the connection carried, for as long as this window is open.
 *
 * The engine keeps the head of every request and the head of its answer behind
 * a switch, and the switch is this screen's lifetime: opening it turns the copy
 * on, closing it turns the copy off. That is the whole reason it is a window
 * and not a setting — a tracker nobody is looking at should not cost a byte.
 *
 * Bodies are not here on purpose: reading one means undoing its transfer and
 * content encoding, which is a second decode of the same response. The two
 * heads, the status and the time answer the question this window exists for:
 * what went where, and what came back.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
fun RequestInspectorScreen(modifier: Modifier = Modifier) {
    var records by remember { mutableStateOf(emptyList<CaptureRecord>()) }
    var selected by remember { mutableStateOf<Long?>(null) }

    // The switch follows the window, not a preference the user has to remember.
    DisposableEffect(Unit) {
        coreCaptureSetEnabled(true)
        onDispose { coreCaptureSetEnabled(false) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            records = coreCaptureRecords()
            if (selected == null && records.isNotEmpty()) {
                selected = records.last().id
            }
            // Half a second is the pace a person reads at; polling faster only
            // copies the same ring more often.
            delay(500)
        }
    }

    val current = records.firstOrNull { it.id == selected } ?: records.lastOrNull()

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                L10n.get(Strings.inspector_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                coreCaptureClear()
                records = emptyList()
                selected = null
            }) {
                Text(L10n.get(Strings.inspector_clear))
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(360.dp).fillMaxHeight()) {
                items(records, key = { it.id }) { record ->
                    val tint =
                        if (record.id == current?.id) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(tint)
                            .clickable { selected = record.id }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${record.status}",
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                if (record.status in 200..399) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                        Text(
                            record.url,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HorizontalDivider()
                }
            }
            HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (current == null) {
                    Text(
                        L10n.get(Strings.inspector_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        HeadBlock(Strings.inspector_request, current.requestHead)
                        HeadBlock(Strings.inspector_response, current.responseHead)
                    }
                }
            }
        }
    }
}

/** One side of the exchange, verbatim, in a fixed pitch so the columns line up. */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun HeadBlock(
    title: String,
    head: String,
) {
    Text(
        L10n.get(title),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
    Text(
        head.ifEmpty { "—" },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
    )
}
