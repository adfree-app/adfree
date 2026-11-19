// The composables here are named the way the panel reads — Headers, Request,
// Response — and ktlint wants a verb. A name that says what it draws is worth
// more than the rule.
@file:Suppress("ktlint:standard:function-naming")

package git.shin.adfree.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import git.shin.adfree.core.CaptureRecord
import git.shin.adfree.core.coreCaptureClear
import git.shin.adfree.core.coreCaptureRecords
import git.shin.adfree.core.coreCaptureSetEnabled
import androidx.compose.ui.window.Dialog
import git.shin.adfree.l10n.L10n
import git.shin.adfree.l10n.Strings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * A connection panel shaped like a browser's own network dock: a toolbar, a
 * column header, one compact row per request, and the selected exchange laid
 * out underneath as sections of name/value pairs.
 *
 * It borrows Chrome DevTools' palette on purpose — the rows read at a glance
 * beside a browser, and the same conventions (a red edge on a failed request, a
 * monospace size column, a tab strip) mean nothing has to be learned twice.
 *
 * The engine copies heads only while this window exists: opening it turns the
 * copy on, closing it turns the copy off. The body is shown as the origin
 * framed it — still compressed, still chunked — because undoing that is a
 * second decode of the same response.
 *
 * The newest request sits at the top, where a trace is watched from: a window
 * left open on a busy page keeps its newest arrival under the reader's eye
 * instead of below the fold. The filter narrows the list without hiding what
 * was captured — the count still says how much there is.
 */
private object Dt {
    val panel = Color(0xFF1F1F1F)
    val toolbar = Color(0xFF292A2D)
    val rowSelected = Color(0xFF37474F)
    val rowHover = Color(0xFF2A2B2E)
    val border = Color(0xFF3C4043)
    val text = Color(0xFFE8EAED)
    val muted = Color(0xFF9AA0A6)
    val link = Color(0xFF8AB4F8)
    val ok = Color(0xFF81C995)
    val bad = Color(0xFFF28B82)
    val warn = Color(0xFFFDD663)
}

@Composable
fun RequestInspectorScreen(modifier: Modifier = Modifier) {
    var records by remember { mutableStateOf(emptyList<CaptureRecord>()) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var opened by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }

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
            delay(500)
        }
    }

    val current = records.firstOrNull { it.id == selected } ?: records.lastOrNull()
    // The ring hands them over oldest first, which is the order they arrived in.
    // Read from the other end so the newest line is the first one on screen.
    val shown = records.filter { matches(it, query) }.asReversed()

    // Where the exchange details sit: below the list like the default browser
    // dock, or pinned to either side. The split remembers what the user set it
    // to; turning it means the pick starts over from the sensible default. On a
    // narrow window there is no room for a permanent pane, so the details move
    // into a dialog that opens over the list.
    var dock by remember { mutableStateOf(Dock.BOTTOM) }
    var picked by remember { mutableStateOf<Dp?>(null) }

    BoxWithConstraints(modifier.fillMaxSize().background(Dt.panel)) {
        val compact = maxWidth < 600.dp
        val axis = if (dock == Dock.BOTTOM) maxHeight else maxWidth
        val density = LocalDensity.current
        val detail = if (compact) 0.dp else (picked ?: (axis * 0.42f)).coerceIn(140.dp, axis * 0.78f)
        // A drag that opens the pane grows it: at the bottom the divider moves
        // up with the pointer, on the right it moves in from the right, and on
        // the left the pane's own edge is the divider.
        val drag =
            rememberDraggableState { delta ->
                val step = with(density) { delta.toDp() }
                picked =
                    (detail + if (dock == Dock.LEFT) step else -step)
                        .coerceIn(140.dp, axis * 0.78f)
            }

        Column(Modifier.fillMaxSize()) {
            Toolbar(
                count = records.size,
                shown = shown.size,
                query = query,
                onQuery = { query = it },
                dock = if (compact) null else dock,
                onDock = {
                    dock = it
                    picked = null
                },
                onClear = {
                    coreCaptureClear()
                    records = emptyList()
                    selected = null
                },
            )
            if (compact) {
                RequestList(
                    records = shown,
                    current = current,
                    onSelect = {
                        selected = it
                        opened = it
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    compact = true,
                )
                val openRecord = opened?.let { id -> records.firstOrNull { it.id == id } }
                if (openRecord != null) {
                    // The window itself cannot tell us where it sits, so the
                    // details ride in a dialog and cover the list while they
                    // are open, the way a phone's detail view does.
                    Dialog(onDismissRequest = { opened = null }) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.88f)
                                .background(Dt.panel)
                                .clip(RoundedCornerShape(10.dp))
                                .padding(4.dp),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .background(Dt.toolbar)
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    nameCell(openRecord.url),
                                    fontSize = 12.sp,
                                    color = Dt.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .clickable { opened = null },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = L10n.get(Strings.common_close),
                                        tint = Dt.muted,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            DetailPane(openRecord, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                }
            } else {
                when (dock) {
                    Dock.BOTTOM -> {
                        RequestList(shown, current, { selected = it }, Modifier.weight(1f))
                        SplitHandle(vertical = false, drag = drag)
                        DetailSlot(current, Modifier.fillMaxWidth().height(detail))
                    }

                    Dock.RIGHT -> {
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            RequestList(shown, current, { selected = it }, Modifier.weight(1f).fillMaxHeight())
                            SplitHandle(vertical = true, drag = drag)
                            DetailSlot(current, Modifier.width(detail).fillMaxHeight())
                        }
                    }

                    Dock.LEFT -> {
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            DetailSlot(current, Modifier.width(detail).fillMaxHeight())
                            SplitHandle(vertical = true, drag = drag)
                            RequestList(shown, current, { selected = it }, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

/** The two pieces above the toolbar: first the list, then the exchange. */
@Composable
private fun RequestList(
    records: List<CaptureRecord>,
    current: CaptureRecord?,
    onSelect: (Long) -> Unit,
    modifier: Modifier,
    compact: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        ColumnHeader(compact)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(records, key = { it.id }) { record ->
                RequestRow(
                    record = record,
                    selected = record.id == current?.id,
                    onClick = { onSelect(record.id) },
                    compact = compact,
                )
            }
        }
    }
}

/** The divider itself is a thin line; the whole strip carries the drag. */
@Composable
private fun SplitHandle(
    vertical: Boolean,
    drag: DraggableState,
) {
    val size = 5.dp
    Box(
        if (vertical) {
            Modifier
                .width(size)
                .fillMaxHeight()
                .draggable(state = drag, orientation = Orientation.Horizontal)
        } else {
            Modifier
                .fillMaxWidth()
                .height(size)
                .draggable(state = drag, orientation = Orientation.Vertical)
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            if (vertical) {
                Modifier.fillMaxHeight().width(1.dp).background(Dt.border)
            } else {
                Modifier.fillMaxWidth().height(1.dp).background(Dt.border)
            },
        )
    }
}

@Composable
private fun DetailSlot(
    current: CaptureRecord?,
    modifier: Modifier,
) {
    if (current == null) {
        Text(
            L10n.get(Strings.inspector_empty),
            fontSize = 12.sp,
            color = Dt.muted,
            modifier = modifier.padding(14.dp),
        )
    } else {
        DetailPane(current, modifier)
    }
}

/** Where the details dock: the browser's own three homes for its panel. */
private enum class Dock { BOTTOM, RIGHT, LEFT }

@Composable
private fun Toolbar(
    count: Int,
    shown: Int,
    query: String,
    onQuery: (String) -> Unit,
    dock: Dock?,
    onDock: (Dock) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(Dt.toolbar)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The filter takes what is left rather than a fixed 240 dp, so the
        // buttons at the other end are never pushed past the window edge. It
        // still refuses to grow past a readable width.
        FilterBox(
            query,
            onQuery,
            Modifier
                .weight(1f, fill = false)
                .width(240.dp),
        )
        Text(
            // A filter that hides rows still says how many there are: a list
            // narrowed to nothing must not read like a capture that caught
            // nothing.
            if (shown == count) {
                L10n.format(Strings.inspector_count, count)
            } else {
                L10n.format(Strings.inspector_count, shown) + " / $count"
            },
            fontSize = 11.sp,
            color = Dt.muted,
        )
        // Fixed 20 dp boxes rather than Material buttons: an icon button has a
        // 48 dp touch target, and three of those plus a dock switcher is more
        // room than a toolbar this short has to give.
        if (dock != null) {
            Dock.entries.forEach { entry ->
                DockButton(entry, entry == dock) { onDock(entry) }
            }
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.DeleteSweep,
                contentDescription = L10n.get(Strings.inspector_clear),
                tint = Dt.muted,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * One of the three dock positions, drawn as the layout it means: a rectangle
 * with the detail pane filled in at the bottom, the right or the left. Drawn
 * rather than borrowed from an icon font, because the shape is the choice.
 */
@Composable
private fun DockButton(
    dock: Dock,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) Dt.link else Dt.muted
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Dt.rowSelected else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(11.dp)) {
            val stroke = 1.dp.toPx()
            drawRect(tint, style = Stroke(stroke))
            val filled =
                when (dock) {
                    Dock.BOTTOM -> Rect(0f, size.height * 0.6f, size.width, size.height)
                    Dock.RIGHT -> Rect(size.width * 0.6f, 0f, size.width, size.height)
                    Dock.LEFT -> Rect(0f, 0f, size.width * 0.4f, size.height)
                }
            drawRect(tint, topLeft = filled.topLeft, size = filled.size)
        }
    }
}

/**
 * The filter box, shaped like the one above a browser's network list: a quiet
 * field that takes a fragment of a URL, a status or a type, and forgets it when
 * the text is deleted.
 */
@Composable
private fun FilterBox(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Dt.panel)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (query.isEmpty()) {
            Text(
                L10n.get(Strings.inspector_search),
                fontSize = 11.sp,
                color = Dt.muted,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = Dt.text, fontSize = 11.sp),
            cursorBrush = SolidColor(Dt.link),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Fixed widths keep every column on a shared edge down the list. */
private val statusWidth = 72.dp
private val typeWidth = 86.dp
private val sizeWidth = 90.dp
private val timeWidth = 96.dp

@Composable
private fun ColumnHeader(compact: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(Dt.toolbar)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HeaderCell(L10n.get(Strings.inspector_col_name), Modifier.weight(1f))
        HeaderCell(L10n.get(Strings.inspector_col_status), Modifier.width(statusWidth))
        if (!compact) HeaderCell(L10n.get(Strings.inspector_col_type), Modifier.width(typeWidth))
        HeaderCell(L10n.get(Strings.inspector_col_size), Modifier.width(sizeWidth))
        HeaderCell(L10n.get(Strings.inspector_col_time), Modifier.width(timeWidth))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Dt.border))
}

@Composable
private fun HeaderCell(
    title: String,
    modifier: Modifier,
) {
    Text(
        title,
        fontSize = 11.sp,
        color = Dt.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun RequestRow(
    record: CaptureRecord,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    val failed = record.status == 0 || record.status >= 400
    Row(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(if (selected) Dt.rowSelected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // DevTools marks a failed request with a red left edge; here it is the
        // one stripe that survives a narrow window.
        Box(Modifier.width(2.dp).fillMaxHeight().background(if (failed) Dt.bad else Color.Transparent))
        Text(
            nameCell(record.url),
            fontSize = 12.sp,
            color = Dt.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (record.status == 0) "pending" else "${record.status}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color =
                when {
                    record.status == 0 -> Dt.muted
                    record.status >= 400 -> Dt.bad
                    record.status >= 300 -> Dt.warn
                    else -> Dt.ok
                },
            maxLines = 1,
            modifier = Modifier.width(statusWidth),
        )
        if (!compact) {
            Text(
                mediaType(record.responseHead),
                fontSize = 11.sp,
                color = Dt.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(typeWidth),
            )
        }
        Text(
            bytes(record.bodyLen),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Dt.muted,
            maxLines = 1,
            modifier = Modifier.width(sizeWidth),
        )
        Text(
            clock(record.tsMs),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Dt.muted,
            maxLines = 1,
            modifier = Modifier.width(timeWidth),
        )
    }
}

@Composable
private fun DetailPane(
    record: CaptureRecord,
    modifier: Modifier,
) {
    var tab by remember(record.id) { mutableStateOf(Tab.HEADERS) }
    Column(modifier.background(Dt.panel)) {
        TabStrip(tab) { tab = it }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Dt.border))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            when (tab) {
                Tab.HEADERS -> {
                    Section(L10n.get(Strings.inspector_section_general))
                    Pair(L10n.get(Strings.inspector_general_url), record.url, Dt.link)
                    Pair(
                        L10n.get(Strings.inspector_general_status),
                        if (record.status == 0) "pending" else "${record.status}",
                        if (record.status >= 400) Dt.bad else Dt.text,
                    )
                    HeaderSection(
                        L10n.get(Strings.inspector_section_response_headers),
                        headerRows(record.responseHead),
                    )
                }

                Tab.REQUEST -> {
                    HeaderSection(null, headerRows(record.requestHead))
                }

                Tab.RESPONSE -> {
                    if (record.bodyPreview.isEmpty()) {
                        Pair(L10n.get(Strings.inspector_col_size), bytes(record.bodyLen), Dt.text)
                    } else {
                        Text(
                            record.bodyPreview,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Dt.text,
                        )
                        Text(
                            bytes(record.bodyLen),
                            fontSize = 11.sp,
                            color = Dt.muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private enum class Tab { HEADERS, REQUEST, RESPONSE }

@Composable
private fun TabStrip(
    selected: Tab,
    onSelect: (Tab) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Dt.toolbar)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { entry ->
            val on = entry == selected
            // The underline is drawn under the word, not laid out as a child
            // of it: a fillMaxWidth box inside this column would have made the
            // column the whole row's width and pushed every other tab past the
            // right edge.
            Text(
                L10n.get(
                    when (entry) {
                        Tab.HEADERS -> Strings.inspector_tab_headers
                        Tab.REQUEST -> Strings.inspector_tab_request
                        Tab.RESPONSE -> Strings.inspector_tab_response
                    },
                ),
                fontSize = 11.sp,
                color = if (on) Dt.text else Dt.muted,
                modifier =
                    Modifier
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .drawBehind {
                            if (!on) return@drawBehind
                            drawRect(
                                color = Dt.link,
                                topLeft = Offset(0f, size.height - 2.dp.toPx()),
                                size = Size(size.width, 2.dp.toPx()),
                            )
                        },
            )
        }
    }
}

/** A DevTools section heading: small, grey, above its own block of rows. */
@Composable
private fun Section(title: String) {
    Text(
        title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = Dt.muted,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(Dt.border))
}

@Composable
private fun HeaderSection(
    title: String?,
    rows: List<Pair<String, String>>,
) {
    if (rows.isEmpty()) {
        if (title != null) Section(title)
        Pair("—", "—", Dt.muted)
        return
    }
    if (title != null) Section(title)
    rows.forEach { (name, value) -> Pair(name, value, Dt.text) }
}

/** One name/value row: the name stays quiet, the value carries the text. */
@Composable
private fun Pair(
    name: String,
    value: String,
    valueColor: Color,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            name,
            fontSize = 11.sp,
            color = Dt.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(150.dp),
        )
        Text(
            value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Whether a row answers the filter. A fragment of the URL is the common case;
 * a bare `404` or `json` is the one that finds what the eye already spotted.
 */
private fun matches(
    record: CaptureRecord,
    query: String,
): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    return record.url.lowercase().contains(needle) ||
        "${record.status}".contains(needle) ||
        mediaType(record.responseHead).lowercase().contains(needle)
}

/**
 * The path in the row's own colour and the host in grey behind it, the way a
 * browser's network list separates the file from the site it came from.
 */
private fun nameCell(url: String): androidx.compose.ui.text.AnnotatedString {
    val host = url.substringAfter("://").substringBefore('/')
    val path = "/" + url.substringAfter("://").substringAfter('/', "")
    return buildAnnotatedString {
        append(path)
        if (host.isNotEmpty()) {
            withStyle(SpanStyle(color = Dt.muted)) { append("  $host") }
        }
    }
}

/** `Key: Value` rows of a head, the status line left out. */
private fun headerRows(head: String): List<Pair<String, String>> =
    head.lines().drop(1).mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) {
            null
        } else {
            line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
    }

/**
 * The resource kind DevTools would print, derived from the answer's own
 * `Content-Type` — it is in the head we already keep, so nothing is fetched to
 * learn it.
 */
private fun mediaType(head: String): String {
    val contentType =
        head
            .lines()
            .firstOrNull { it.startsWith("content-type:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.lowercase()
            ?: return "—"
    return when {
        "html" in contentType -> "document"
        "css" in contentType -> "stylesheet"
        "javascript" in contentType || "ecmascript" in contentType -> "script"
        "json" in contentType -> "json"
        contentType.startsWith("image/") -> "img"
        "font" in contentType || "woff" in contentType || contentType.startsWith("application/vnd.ms-fontobject") -> "font"
        contentType.startsWith("text/") -> "text"
        else -> "other"
    }
}

/**
 * The wall clock the request started at, to the millisecond: a tracker is read
 * beside a page load and beside another row, and both answers are in the
 * fractions of a second.
 */
private fun clock(tsMs: Long): String =
    if (tsMs <= 0) {
        "—"
    } else {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(tsMs))
    }

/** Bytes as DevTools prints them: one decimal, the unit it has earned. */
private fun bytes(count: Long): String =
    when {
        count <= 0 -> "—"
        count < 1024 -> "$count B"
        count < 1024 * 1024 -> "${(count / 102.4).toInt() / 10.0} kB"
        else -> "${(count / 104857.6).toInt() / 10.0} MB"
    }
