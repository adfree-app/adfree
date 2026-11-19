// The composables here are named the way the panel reads — Headers, Request,
// Response — and ktlint wants a verb. A name that says what it draws is worth
// more than the rule.
@file:Suppress("ktlint:standard:function-naming")

package git.shin.adfree.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import git.shin.adfree.core.CaptureRecord
import git.shin.adfree.core.WsFrame
import git.shin.adfree.core.coreCaptureClear
import git.shin.adfree.core.coreCaptureRecords
import git.shin.adfree.core.coreCaptureSetEnabled
import git.shin.adfree.data.InspectorDock
import git.shin.adfree.data.InspectorLayout
import git.shin.adfree.l10n.L10n
import git.shin.adfree.l10n.Strings
import git.shin.adfree.model.RequestCopy
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
fun RequestInspectorScreen(
    modifier: Modifier = Modifier,
    layout: InspectorLayout? = null,
) {
    var records by remember { mutableStateOf(emptyList<CaptureRecord>()) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var opened by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Kind.ALL) }
    // The tab belongs to the reader, not to the row: whatever they were reading
    // is what the next request opens on. A tab a request cannot have — a
    // socket's messages, asked of a plain answer — falls back to the headers
    // for that request and is still remembered for the next one.
    var tab by remember { mutableStateOf(Tab.HEADERS) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(0) }

    // The confirmation is the whole of the feedback: a copy leaves no other
    // trace, and a value that silently lands on the clipboard is a value nobody
    // trusts. The token retriggers the countdown when the same text is copied
    // twice.
    val copy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        copied += 1
    }
    LaunchedEffect(copied) {
        if (copied > 0) {
            delay(1500)
            copied = 0
        }
    }

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
    // Both narrowings at once: the text filter answers "which one was that",
    // the kind row answers "what did this page load".
    val shown =
        records
            .filter { kind == Kind.ALL || kindOf(it) == kind }
            .filter { matches(it, query) }
            .asReversed()

    // Where the exchange details sit: below the list like the default browser
    // dock, or pinned to either side. The split remembers what the user set it
    // to; turning it means the pick starts over from the sensible default. On a
    // narrow window there is no room for a permanent pane, so the details move
    // into a dialog that opens over the list.
    // A pane that opens where the defaults put it has to be dragged there on
    // every open, so the last position — and the last size — are read back from
    // the store before the first frame.
    var dock by remember { mutableStateOf(layout?.dock() ?: InspectorDock.BOTTOM) }
    var picked by remember { mutableStateOf<Dp?>(null) }

    BoxWithConstraints(modifier.fillMaxSize().background(Dt.panel)) {
        val compact = maxWidth < 600.dp
        val axis = if (dock == InspectorDock.BOTTOM) maxHeight else maxWidth
        val density = LocalDensity.current
        val ratio = layout?.ratio() ?: InspectorLayout.DEFAULT_RATIO
        val detail =
            if (compact) {
                0.dp
            } else {
                (picked ?: (axis * ratio)).coerceIn(140.dp, axis * InspectorLayout.MAX_RATIO)
            }
        // Saved after the drag settles, not on every delta: a properties file
        // written per mouse move is a file written a thousand times a second.
        LaunchedEffect(dock, picked) {
            if (!compact && axis > 0.dp) {
                delay(400)
                layout?.remember(dock, detail.value / axis.value)
            }
        }
        // A drag that opens the pane grows it: at the bottom the divider moves
        // up with the pointer, on the right it moves in from the right, and on
        // the left the pane's own edge is the divider.
        val drag =
            rememberDraggableState { delta ->
                val step = with(density) { delta.toDp() }
                picked =
                    (detail + if (dock == InspectorDock.LEFT) step else -step)
                        .coerceIn(140.dp, axis * InspectorLayout.MAX_RATIO)
            }

        Column(Modifier.fillMaxSize()) {
            Toolbar(
                count = records.size,
                shown = shown.size,
                query = query,
                onQuery = { query = it },
                dock = if (compact) null else dock,
                onDock = { dock = it },
                kind = kind,
                onKind = { kind = it },
                onClear = {
                    coreCaptureClear()
                    records = emptyList()
                    selected = null
                },
            )
            KindRow(kind) { kind = it }
            if (compact) {
                RequestList(
                    records = shown,
                    current = current,
                    onSelect = {
                        selected = it
                        opened = it
                    },
                    onCopy = copy,
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
                            DetailPane(openRecord, tab, { tab = it }, copy, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                }
            } else {
                when (dock) {
                    InspectorDock.BOTTOM -> {
                        RequestList(shown, current, { selected = it }, copy, Modifier.weight(1f))
                        SplitHandle(vertical = false, drag = drag)
                        DetailSlot(current, tab, { tab = it }, copy, Modifier.fillMaxWidth().height(detail))
                    }

                    InspectorDock.RIGHT -> {
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            RequestList(shown, current, { selected = it }, copy, Modifier.weight(1f).fillMaxHeight())
                            SplitHandle(vertical = true, drag = drag)
                            DetailSlot(current, tab, { tab = it }, copy, Modifier.width(detail).fillMaxHeight())
                        }
                    }

                    InspectorDock.LEFT -> {
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            DetailSlot(current, tab, { tab = it }, copy, Modifier.width(detail).fillMaxHeight())
                            SplitHandle(vertical = true, drag = drag)
                            RequestList(shown, current, { selected = it }, copy, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
        if (copied > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Dt.rowSelected)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(L10n.get(Strings.inspector_copied), fontSize = 11.sp, color = Dt.text)
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
    onCopy: (String) -> Unit,
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
                    onCopy = onCopy,
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
    tab: Tab,
    onTab: (Tab) -> Unit,
    onCopy: (String) -> Unit,
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
        DetailPane(current, tab, onTab, onCopy, modifier)
    }
}

/**
 * The kinds a request can be filed under, guessed from what the answer claims
 * to be carrying. The browser knows a resource's type because it asked for it;
 * a tracker watching bytes only has the content type, which is why this is a
 * filter a reader narrows by hand rather than a fact the row states.
 */
private enum class Kind { ALL, XHR, JS, CSS, DOC, WS, IMG, FONT, OTHER }

@Composable
private fun Toolbar(
    count: Int,
    shown: Int,
    query: String,
    onQuery: (String) -> Unit,
    dock: InspectorDock?,
    onDock: (InspectorDock) -> Unit,
    kind: Kind,
    onKind: (Kind) -> Unit,
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
            InspectorDock.entries.forEach { entry ->
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
 * The kinds a request can be narrowed to, in a row of chips the way a browser
 * lays them out. The row scrolls sideways rather than wrapping: eight chips do
 * not fit a narrow window, and a second line would eat the list.
 */
@Composable
private fun KindRow(
    kind: Kind,
    onKind: (Kind) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(Dt.toolbar)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Kind.entries.forEach { entry ->
            val selected = entry == kind
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) Dt.rowSelected else Color.Transparent)
                    .clickable { onKind(entry) }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(
                    L10n.get(kindLabel(entry)),
                    fontSize = 11.sp,
                    color = if (selected) Dt.text else Dt.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun kindLabel(kind: Kind): String =
    when (kind) {
        Kind.ALL -> Strings.inspector_type_all
        Kind.XHR -> Strings.inspector_type_xhr
        Kind.JS -> Strings.inspector_type_js
        Kind.CSS -> Strings.inspector_type_css
        Kind.DOC -> Strings.inspector_type_doc
        Kind.WS -> Strings.inspector_type_ws
        Kind.IMG -> Strings.inspector_type_img
        Kind.FONT -> Strings.inspector_type_font
        Kind.OTHER -> Strings.inspector_type_other
    }

/**
 * What the answer claims to be carrying. A response with no head yet cannot be
 * filed, so it lands in `OTHER` until its own row updates — which is also what
 * a request still waiting to be answered looks like. A socket that switched
 * protocols answered with no `Content-Type` at all: the status is the only
 * thing that says what it is.
 */
private fun kindOf(record: CaptureRecord): Kind {
    val type = mediaType(record.responseHead).lowercase()
    return when {
        record.status == 101 -> Kind.WS
        record.responseHead.isEmpty() -> Kind.OTHER
        "javascript" in type || "ecmascript" in type -> Kind.JS
        "text/css" in type -> Kind.CSS
        "text/html" in type || "xhtml" in type -> Kind.DOC
        "image/" in type -> Kind.IMG
        "font" in type || "woff" in type || "ttf" in type || "opentype" in type -> Kind.FONT
        "json" in type || "xml" in type || "text/plain" in type -> Kind.XHR
        else -> Kind.OTHER
    }
}

/**
 * One of the three dock positions, drawn as the layout it means: a rectangle
 * with the detail pane filled in at the bottom, the right or the left. Drawn
 * rather than borrowed from an icon font, because the shape is the choice.
 */
@Composable
private fun DockButton(
    dock: InspectorDock,
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
                    InspectorDock.BOTTOM -> Rect(0f, size.height * 0.6f, size.width, size.height)
                    InspectorDock.RIGHT -> Rect(size.width * 0.6f, 0f, size.width, size.height)
                    InspectorDock.LEFT -> Rect(0f, 0f, size.width * 0.4f, size.height)
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

// Fixed widths keep every column on a shared edge down the list. The method
// column reads "Phương thức" in Vietnamese, which is wider than the flag
// itself; a phone's row only ever holds "GET" or "POST".
private val methodWidth = 104.dp
private val methodWidthCompact = 54.dp
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
        // On a phone the column only ever holds GET or POST, so its header
        // stays blank rather than truncating the long Vietnamese word.
        HeaderCell(
            if (compact) "" else L10n.get(Strings.inspector_col_method),
            Modifier.width(if (compact) methodWidthCompact else methodWidth),
        )
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
    onCopy: (String) -> Unit,
    compact: Boolean = false,
) {
    val failed = record.status == 0 || record.status >= 400
    var menu by remember { mutableStateOf(false) }
    // A menu that opens at the row's left edge opens at the same place every
    // time, however far along the row the pointer was. The press carries the
    // position, so the menu can be put where the pointer already is.
    var menuAt by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(if (selected) Dt.rowSelected else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .pointerInput(record.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) {
                                event.changes.firstOrNull()?.let { menuAt = it.position }
                                if (event.buttons.isSecondaryPressed) menu = true
                            }
                        }
                    }
                }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // DevTools marks a failed request with a red left edge; here it is
            // the one stripe that survives a narrow window.
            Box(Modifier.width(2.dp).fillMaxHeight().background(if (failed) Dt.bad else Color.Transparent))
            // The method in its own column, coloured the way a reader already
            // reads HTTP: a read is green, a write is yellow, a deletion is
            // red. The name keeps whatever is left.
            Text(
                record.method.ifEmpty { "GET" },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color =
                    when (record.method.uppercase()) {
                        "GET", "HEAD" -> Dt.ok
                        "POST", "PUT", "PATCH" -> Dt.warn
                        "DELETE" -> Dt.bad
                        else -> Dt.muted
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(if (compact) methodWidthCompact else methodWidth),
            )
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
                    typeName(record),
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
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            offset =
                with(density) {
                    DpOffset(menuAt.x.toDp(), menuAt.y.toDp())
                },
        ) {
            DropdownMenuItem(
                text = { Text(L10n.get(Strings.inspector_copy_fetch), fontSize = 12.sp, color = Dt.text) },
                onClick = {
                    menu = false
                    onCopy(RequestCopy.fetch(record.url, record.requestHead))
                },
            )
            DropdownMenuItem(
                text = { Text(L10n.get(Strings.inspector_copy_curl), fontSize = 12.sp, color = Dt.text) },
                onClick = {
                    menu = false
                    onCopy(RequestCopy.curlBash(record.url, record.requestHead))
                },
            )
            DropdownMenuItem(
                text = { Text(L10n.get(Strings.inspector_copy_curl_cmd), fontSize = 12.sp, color = Dt.text) },
                onClick = {
                    menu = false
                    onCopy(RequestCopy.curlCmd(record.url, record.requestHead))
                },
            )
            DropdownMenuItem(
                text = { Text(L10n.get(Strings.inspector_copy_powershell), fontSize = 12.sp, color = Dt.text) },
                onClick = {
                    menu = false
                    onCopy(RequestCopy.powershell(record.url, record.requestHead))
                },
            )
            DropdownMenuItem(
                text = { Text(L10n.get(Strings.inspector_copy_head), fontSize = 12.sp, color = Dt.text) },
                onClick = {
                    menu = false
                    onCopy(record.requestHead)
                },
            )
        }
    }
}

@Composable
private fun DetailPane(
    record: CaptureRecord,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.background(Dt.panel)) {
        val tabs = tabsFor(record)
        // What this request can show, which is not always what was asked for.
        val shown = if (tab in tabs) tab else Tab.HEADERS
        TabStrip(shown, onTab, tabs)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Dt.border))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            when (shown) {
                Tab.HEADERS -> {
                    Section(L10n.get(Strings.inspector_section_general))
                    Pair(L10n.get(Strings.inspector_general_url), record.url, Dt.link, onCopy)
                    Pair(
                        L10n.get(Strings.inspector_general_status),
                        if (record.status == 0) "pending" else "${record.status}",
                        if (record.status >= 400) Dt.bad else Dt.text,
                        onCopy,
                    )
                    HeaderSection(
                        L10n.get(Strings.inspector_section_response_headers),
                        headerRows(record.responseHead),
                        onCopy,
                    )
                }

                Tab.REQUEST -> {
                    HeaderSection(null, headerRows(record.requestHead), onCopy)
                    if (record.requestBodyPreview.isNotEmpty()) {
                        Section(L10n.get(Strings.inspector_section_request_body))
                        Text(
                            record.requestBodyPreview,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Dt.text,
                            modifier = Modifier.clickable { onCopy(record.requestBodyPreview) },
                        )
                        Text(
                            bytes(record.requestBodyLen),
                            fontSize = 11.sp,
                            color = Dt.muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                Tab.MESSAGES -> {
                    if (record.frames.isEmpty()) {
                        Text(L10n.get(Strings.inspector_ws_empty), fontSize = 11.sp, color = Dt.muted)
                    } else {
                        record.frames.forEach { frame -> MessageRow(frame, onCopy) }
                    }
                }

                Tab.RESPONSE -> {
                    if (record.bodyPreview.isEmpty()) {
                        Pair(L10n.get(Strings.inspector_col_size), bytes(record.bodyLen), Dt.text, onCopy)
                    } else {
                        Text(
                            record.bodyPreview,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Dt.text,
                            modifier = Modifier.clickable { onCopy(record.bodyPreview) },
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

/**
 * One frame of an upgraded socket: which way it went, what kind it was, how
 * long it was and the beginning of what it said. Clicking it copies the text.
 */
@Composable
private fun MessageRow(
    frame: WsFrame,
    onCopy: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (frame.preview.isNotEmpty()) onCopy(frame.preview) }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (frame.outgoing) "\u2191" else "\u2193",
            fontSize = 12.sp,
            color = if (frame.outgoing) Dt.ok else Dt.link,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            opcodeName(frame.opcode),
            fontSize = 11.sp,
            color = Dt.text,
            modifier = Modifier.width(56.dp),
        )
        Text(
            bytes(frame.len),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Dt.muted,
            maxLines = 1,
        )
        Text(
            frame.preview.ifEmpty { "\u00a0" },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Dt.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun opcodeName(opcode: Int): String =
    when (opcode) {
        1 -> "Text"
        2 -> "Binary"
        8 -> "Close"
        9 -> "Ping"
        10 -> "Pong"
        0 -> "Cont."
        else -> "Op $opcode"
    }

private enum class Tab { HEADERS, REQUEST, RESPONSE, MESSAGES }

private fun tabsFor(record: CaptureRecord): List<Tab> =
    if (record.status == 101) {
        listOf(Tab.HEADERS, Tab.REQUEST, Tab.MESSAGES, Tab.RESPONSE)
    } else {
        listOf(Tab.HEADERS, Tab.REQUEST, Tab.RESPONSE)
    }

@Composable
private fun TabStrip(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    tabs: List<Tab>,
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
        tabs.forEach { entry ->
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
                        Tab.MESSAGES -> Strings.inspector_tab_messages
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
    onCopy: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        if (title != null) Section(title)
        Pair("—", "—", Dt.muted, onCopy)
        return
    }
    if (title != null) Section(title)
    rows.forEach { (name, value) -> Pair(name, value, Dt.text, onCopy) }
}

/** One name/value row: the name stays quiet, the value carries the text. */
@Composable
private fun Pair(
    name: String,
    value: String,
    valueColor: Color,
    onCopy: (String) -> Unit,
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
        // The value is the thing a reader wants out of the window, so it is the
        // thing that copies: one click, and the pill says so.
        Text(
            value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
            modifier =
                Modifier
                    .weight(1f)
                    .clickable { onCopy(value) },
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
        typeName(record).lowercase().contains(needle)
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
 * The type cell's word: an upgrade has no `Content-Type` to read, and a dash
 * there reads as "nothing known" rather than as a socket.
 */
private fun typeName(record: CaptureRecord): String =
    if (record.status == 101) "websocket" else mediaType(record.responseHead)

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
