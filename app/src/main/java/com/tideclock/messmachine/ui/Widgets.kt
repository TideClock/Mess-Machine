package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.R

/** Set by the test suite so screens skip their endless timers. */
object UiFlags { @JvmField var testing = false }

/** Neat Shit and Dye's night palette. */
object C {
    val Ink = Color(0xFF18161A)
    val Paper = Color(0xFF141215)
    val Card = Color(0xFF1E1B21)
    val Card2 = Color(0xFF2E2833)
    val Empty = Color(0xFF3A3440)
    val Cream = Color(0xFFFFF4E2)
    val Dim = Color(0xFFBDB2BD)
    val Pink = Color(0xFFFF4FA3)
    val Teal = Color(0xFF00BEB4)
    val Yellow = Color(0xFFFFD23F)
    val Riot = Color(0xFFFF4A2E)
    val Purple = Color(0xFFC4A1FF)
    val Lime = Color(0xFFB6FF3B)
    val Black = Color(0xFF000000)
    val Letters = listOf(Pink, Teal, Yellow, Purple, Color(0xFF7FD1FF), Color(0xFFFF7A1A), Lime, Riot)
}

val Archivo = FontFamily(
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_extrabold, FontWeight.ExtraBold),
    Font(R.font.archivo_black, FontWeight.Black),
)
val Bungee = FontFamily(Font(R.font.bungee))
val Mono = FontFamily(Font(R.font.plexmono_400, FontWeight.Normal), Font(R.font.plexmono_700, FontWeight.Bold))

fun ink(on: Color): Color = if (on.luminance() > .45f) C.Ink else C.Cream

fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/** Jar colours sit a little darker on the machine, like the site's pads. */
fun padColor(c: Color) = lerp(c, C.Ink, .22f)

/** Roll colours by pitch class: teal to pink to yellow to riot red, like the site's paint palette. */
fun pcColor(pc: Int): Color {
    val k = ((pc % 12) + 12) % 12
    return when {
        k < 4 -> lerp(C.Teal, C.Pink, k / 4f)
        k < 8 -> lerp(C.Pink, C.Yellow, (k - 4) / 4f)
        else -> lerp(C.Yellow, C.Riot, (k - 8) / 4f)
    }
}

/** True while Lock is on: no scrolling, menus or navigation. */
val LocalLocked = compositionLocalOf { false }
/** True while the how-to text should show (? in the top bar, or the first visit to a page). */
val LocalHelp = compositionLocalOf { true }

/** The site's sticker: square, a cream outline, a hard pink shadow. */
fun Modifier.sticker(
    color: Color, radius: Dp = 0.dp, shadow: Dp = 3.dp, border: Dp = 3.dp, borderColor: Color = C.Cream, shadowColor: Color = C.Pink,
): Modifier =
    this
        .drawBehind {
            if (shadow > 0.dp) drawRoundRect(shadowColor, topLeft = Offset(shadow.toPx(), shadow.toPx()), size = size, cornerRadius = CornerRadius(radius.toPx()))
        }
        .background(color, RoundedCornerShape(radius))
        .then(if (border > 0.dp) Modifier.border(border, borderColor, RoundedCornerShape(radius)) else Modifier)

/** A fine grit over the dark background, made once. */
private var gritTile: ImageBitmap? = null
fun gritBrush(): ShaderBrush {
    val t = gritTile ?: run {
        val n = 96
        val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val r = java.util.Random(7)
        for (y in 0 until n) for (x in 0 until n) {
            val v = r.nextInt(100)
            val a = when { v < 6 -> 26; v < 12 -> 14; else -> 0 }
            bmp.setPixel(x, y, android.graphics.Color.argb(a, 255, 244, 226))
        }
        bmp.asImageBitmap().also { gritTile = it }
    }
    return ShaderBrush(ImageShader(t, TileMode.Repeated, TileMode.Repeated))
}

fun Modifier.grit(): Modifier = this.drawBehind { drawRect(gritBrush()) }

@Composable
fun T(
    text: String, modifier: Modifier = Modifier, color: Color = C.Cream, size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.ExtraBold, lines: Int = Int.MAX_VALUE, family: FontFamily = Archivo, spacing: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text, modifier = modifier, color = color, maxLines = lines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = family, fontWeight = weight, fontSize = size, lineHeight = size * 1.25f, letterSpacing = spacing)
    )
}

/** Small lab-notes text, like the site's mono captions. */
@Composable
fun Help(text: String, modifier: Modifier = Modifier) {
    if (LocalHelp.current) Note(text, modifier.testTag("help"), C.Teal)
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = C.Dim, size: TextUnit = 12.sp) {
    T(text, modifier, color = color, size = size, weight = FontWeight.Normal, family = Mono)
}

/** Bungee heading in yellow with a hard pink shadow. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, size: TextUnit = 20.sp, color: Color = C.Yellow) {
    Text(
        text.uppercase(), modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Bungee, fontSize = size, color = color, shadow = Shadow(C.Pink, Offset(4f, 4f), 0f))
    )
}

/** The title with a teal and pink split behind it. */
@Composable
fun GlitchTitle(text: String, size: TextUnit = 22.sp, modifier: Modifier = Modifier) {
    Box(modifier) {
        T(text.uppercase(), color = C.Teal, size = size, family = Bungee, weight = FontWeight.Normal, lines = 1, modifier = Modifier.offset((-2).dp, 0.dp))
        T(text.uppercase(), color = C.Pink, size = size, family = Bungee, weight = FontWeight.Normal, lines = 1, modifier = Modifier.offset(2.dp, 2.dp))
        T(text.uppercase(), color = C.Yellow, size = size, family = Bungee, weight = FontWeight.Normal, lines = 1)
    }
}

/** The site's dashed rule between sections. */
@Composable
fun Dashed(modifier: Modifier = Modifier, color: Color = C.Empty) {
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawLine(color, Offset(0f, 1f), Offset(size.width, 1f), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
    }
}

/**
 * How loud a button looks. Primary: the few you reach for while playing (Play, Rec). Normal: the
 * sticker look. Quiet: openers and settings (?, ☰, ⚙), thin and dim. Danger: clears, red text.
 */
enum class Tier { Primary, Normal, Quiet, Danger }

/** Square, uppercase, cream outline, pink shadow; it sinks into its shadow when pressed. */
@Composable
fun Btn(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.Ink, on: Boolean = false,
    onColor: Color = C.Yellow, enabled: Boolean = true, small: Boolean = false, tag: String? = null, tier: Tier = Tier.Normal,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg = if (on) onColor else color
    val sh = when (tier) { Tier.Primary -> if (small) 4.dp else 5.dp; Tier.Quiet -> 0.dp; else -> if (small) 3.dp else 4.dp }
    val sink = if (pressed && enabled) (if (tier == Tier.Quiet) 1.dp else sh) else if (on && tier != Tier.Quiet) 1.dp else 0.dp
    val edge = when (tier) { Tier.Primary -> if (small) 3.dp else 3.5.dp; Tier.Quiet -> 1.5.dp; else -> if (small) 2.5.dp else 3.dp }
    val edgeColor = when { on -> C.Cream; tier == Tier.Quiet -> C.Empty; tier == Tier.Danger -> C.Riot; else -> C.Cream }
    Box(
        modifier
            .heightIn(min = if (small) 34.dp else 44.dp)
            .offset(sink, sink)
            .sticker(bg, shadow = sh - sink, border = edge, borderColor = edgeColor)
            .alpha(if (enabled) 1f else .38f)
            .clickable(src, indication = null, enabled = enabled) { onClick() }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .semantics { selected = on }
            .padding(horizontal = if (small) 10.dp else 14.dp, vertical = if (small) 6.dp else 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text.uppercase(), color = if (on) ink(bg) else when (tier) { Tier.Quiet -> C.Dim; Tier.Danger -> C.Riot; else -> ink(bg) },
            size = if (small) 12.sp else 13.sp, lines = 1, spacing = .08.em, weight = if (tier == Tier.Primary) FontWeight.Black else FontWeight.ExtraBold)
    }
}

/** A button that opens a short list. */
@Composable
fun <V> Select(
    label: String, options: List<Pair<V, String>>, onPick: (V) -> Unit, modifier: Modifier = Modifier,
    small: Boolean = true, color: Color = C.Ink,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Btn("$label ▾", { open = true }, small = small, color = color, enabled = !LocalLocked.current)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(C.Ink).border(2.dp, C.Cream)) {
            for ((v, name) in options) {
                DropdownMenuItem(
                    text = { T(name, size = 14.sp, weight = FontWeight.Medium) },
                    onClick = { open = false; onPick(v) },
                )
            }
        }
    }
}

/** A panel: ink, cream outline, big pink shadow. */
@Composable
fun Card(modifier: Modifier = Modifier, color: Color = C.Ink, content: @Composable () -> Unit) {
    Box(modifier.padding(end = 6.dp, bottom = 6.dp).sticker(color, shadow = 6.dp).padding(12.dp)) { content() }
}

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, show: (Float) -> String,
              onChange: (Float) -> Unit, onDone: () -> Unit, steps: Int = 0, accent: Color = C.Pink) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(label.uppercase(), size = 12.sp, spacing = .08.em, modifier = Modifier.weight(1f))
            Note(show(value), color = C.Yellow)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            onValueChangeFinished = onDone, steps = steps,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = C.Card2, activeTickColor = C.Ink, inactiveTickColor = C.Empty),
        )
    }
}

/**
 * A scrolling page with a scroll bar you can grab on the right: drag the pink thumb, or tap the
 * track to jump. The content keeps clear of the bar.
 */
@Composable
fun ScrollPane(modifier: Modifier = Modifier, hPad: Dp = 12.dp, vPad: Dp = 12.dp, tag: String = "scrollpane", content: @Composable ColumnScope.() -> Unit) {
    val ss = rememberScrollState()
    val locked = LocalLocked.current
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(ss, enabled = !locked).testTag(tag).padding(start = hPad, top = vPad, bottom = vPad + 24.dp, end = hPad + 14.dp), content = content)
        if (!locked) ScrollBar(ss, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
fun ScrollBar(ss: ScrollState, modifier: Modifier = Modifier) {
    val max = ss.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return
    val scope = rememberCoroutineScope()
    Canvas(
        modifier
            .fillMaxHeight()
            .width(24.dp)
            .testTag("scrollbar")
            .pointerInput(max) {
                detectTapGestures { o ->
                    val track = size.height.toFloat()
                    val thumb = maxOf(56.dp.toPx(), track * track / (track + max))
                    val f = ((o.y - thumb / 2) / (track - thumb)).coerceIn(0f, 1f)
                    scope.launch { ss.animateScrollTo((f * max).toInt()) }
                }
            }
            .pointerInput(max) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    val track = size.height.toFloat()
                    val thumb = maxOf(56.dp.toPx(), track * track / (track + max))
                    scope.launch { ss.scrollBy(dy * max / maxOf(1f, track - thumb)) }
                }
            },
    ) {
        val track = size.height
        val thumb = maxOf(56.dp.toPx(), track * track / (track + max))
        val y = (track - thumb) * ss.value / max
        val x = size.width - 10.dp.toPx()
        drawLine(C.Empty, Offset(x + 3.dp.toPx(), 6f), Offset(x + 3.dp.toPx(), track - 6f), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        drawRect(C.Black, topLeft = Offset(x + 2.dp.toPx(), y + 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(6.dp.toPx(), thumb))
        drawRect(C.Pink, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(6.dp.toPx(), thumb))
        drawRect(C.Cream, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(6.dp.toPx(), thumb), style = Stroke(1.5.dp.toPx()))
    }
}

/** The same bar for long lists (the jars). */
@Composable
fun LazyScrollBar(ls: LazyListState, count: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return
    val scope = rememberCoroutineScope()
    val visible = ls.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    if (visible >= count) return
    Canvas(
        modifier
            .fillMaxHeight()
            .width(24.dp)
            .testTag("scrollbar")
            .pointerInput(count) {
                detectTapGestures { o -> scope.launch { ls.scrollToItem(((o.y / size.height) * count).toInt().coerceIn(0, count - 1)) } }
            }
            .pointerInput(count) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    scope.launch { ls.scrollToItem(((change.position.y / size.height) * count).toInt().coerceIn(0, count - 1)) }
                }
            },
    ) {
        val track = size.height
        val thumb = maxOf(56.dp.toPx(), track * visible / count)
        val y = (track - thumb) * (ls.firstVisibleItemIndex.toFloat() / maxOf(1, count - visible)).coerceIn(0f, 1f)
        val x = size.width - 10.dp.toPx()
        drawLine(C.Empty, Offset(x + 3.dp.toPx(), 6f), Offset(x + 3.dp.toPx(), track - 6f), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        drawRect(C.Black, topLeft = Offset(x + 2.dp.toPx(), y + 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(6.dp.toPx(), thumb))
        drawRect(C.Pink, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(6.dp.toPx(), thumb))
        drawRect(C.Cream, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(6.dp.toPx(), thumb), style = Stroke(1.5.dp.toPx()))
    }
}

/** Buttons that wrap onto a new line instead of hiding off the right edge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Wrap(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable () -> Unit) {
    FlowRow(modifier.padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) { content() }
}

@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * Tap, or hold still and lift for a menu (like a right click). Any slide cancels both, so a page still
 * scrolls under it.
 */
fun Modifier.tapOrHold(key: Any?, onTap: () -> Unit, onHold: () -> Unit): Modifier = this.pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var moved = false
        suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.follow(): Boolean {
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id } ?: return false
                if (c.isConsumed) moved = true
                if (!c.pressed) return true
                if ((c.position - down.position).getDistance() > slop) moved = true
            }
        }
        val early = withTimeoutOrNull(450) { follow() }
        if (early == null) { if (follow() && !moved) onHold() }
        else if (early && !moved) onTap()
    }
}

/** A small right-click style menu: a title, then items; closes itself after a pick. */
@Composable
fun HoldMenu(open: Boolean, title: String, onClose: () -> Unit, items: List<Triple<String, String, () -> Unit>>) {
    DropdownMenu(expanded = open, onDismissRequest = onClose, modifier = Modifier.background(C.Ink).border(3.dp, C.Cream)) {
        T(title.uppercase(), size = 12.sp, color = C.Yellow, lines = 1, spacing = .06.em, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        for ((label, tag, act) in items) {
            DropdownMenuItem(text = { T(label.uppercase(), size = 13.sp, spacing = .06.em) }, onClick = { onClose(); act() }, modifier = Modifier.testTag(tag))
        }
    }
}

/** A sideways scroller whose right edge fades out while there is more to see, so a cut-off row reads as "scroll me". */
fun Modifier.fadeScroll(scroll: ScrollState): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        if (scroll.canScrollForward) {
            val f = 28.dp.toPx().coerceAtMost(size.width / 3)
            drawRect(
                Brush.horizontalGradient(0f to Color.Black, ((size.width - f) / size.width) to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }
    }
    .horizontalScroll(scroll)
