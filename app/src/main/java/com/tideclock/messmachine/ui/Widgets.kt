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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.imageResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
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

/**
 * Colour skins (build 5 of the overhaul). A skin is only colour, grit and shadow; the Look (Classic words or the Pro
 * icon tiles) is the button skin and stays separate, so any skin works with any Look.
 */
class Skin(
    val id: String, val name: String,
    val ink: Long, val paper: Long, val card: Long, val card2: Long, val empty: Long, val cream: Long, val dim: Long,
    val pink: Long, val teal: Long, val yellow: Long, val riot: Long, val purple: Long, val lime: Long, val sky: Long, val orange: Long,
    /** how strong the grit is over the background (1 = Classic) */
    val grit: Float = 1f,
    /** sticker shadows: null keeps each sticker's own colour, otherwise this one (Stage turns them off with alpha 0) */
    val shadow: Long? = null,
    /** how a loaded pad is painted: "jar" (Classic), "glass" (Specimen, a glow), "band" (Stage, black with a colour band) */
    val pad: String = "jar",
    /** a light skin: dark words on paper, and dark icons in the phone's status bar */
    val light: Boolean = false,
)

object Skins {
    val classic = Skin("classic", "Classic", 0xFF18161A, 0xFF141215, 0xFF1E1B21, 0xFF2E2833, 0xFF3A3440, 0xFFFFF4E2, 0xFFBDB2BD,
        0xFFFF4FA3, 0xFF00BEB4, 0xFFFFD23F, 0xFFFF4A2E, 0xFFC4A1FF, 0xFFB6FF3B, 0xFF7FD1FF, 0xFFFF7A1A)
    /** Specimen: a lab notebook at night. Green black glass, bone white, colours with the shine taken off */
    val specimen = Skin("specimen", "Specimen", 0xFF151D1A, 0xFF0E0F0C, 0xFF1A2320, 0xFF24302B, 0xFF3A4440, 0xFFE6DFCB, 0xFFA39D8C,
        0xFFE0409A, 0xFF2FB8A6, 0xFFD9B54A, 0xFFC4552B, 0xFFB79CFF, 0xFFB7E23A, 0xFF6FB8D9, 0xFFD9772E, grit = 1.8f, shadow = 0xCC000000, pad = "glass")
    /** Stage: for dark rooms. Pure black, white text, loud colours, no grit and no shadows */
    val stage = Skin("stage", "Stage", 0xFF0D0D0D, 0xFF000000, 0xFF000000, 0xFF1A1A1A, 0xFF4A4A4A, 0xFFFFFFFF, 0xFFC8C8C8,
        0xFFFF2FA0, 0xFF00E5FF, 0xFFFFE600, 0xFFFF6A00, 0xFFB48CFF, 0xFFA6FF00, 0xFF5AC8FF, 0xFFFF8A00, grit = 0f, shadow = 0x00000000, pad = "band")
    /** Felt and Paper: daylight. Warm paper, ink words, pads like felt squares. Cream is the words colour, so it is dark here */
    val felt = Skin("felt", "Felt and Paper", 0xFFDCD3BF, 0xFFE9E2D0, 0xFFF3EEE2, 0xFFD8CDB6, 0xFFA89F8C, 0xFF2A2522, 0xFF6B6358,
        0xFFC9356F, 0xFF12857B, 0xFFA87A06, 0xFFBE3D1C, 0xFF6A4CC4, 0xFF5A8A10, 0xFF2F7FB0, 0xFFC0661A, grit = 0f, shadow = 0x40000000, pad = "felt", light = true)
    /** Rust: a machine left out in the rain. Oxide browns, verdigris, chalky words */
    val rust = Skin("rust", "Rust", 0xFF1C1411, 0xFF140E0B, 0xFF231915, 0xFF33241D, 0xFF4A372D, 0xFFEADBC8, 0xFFB09A86,
        0xFFD9572B, 0xFF4FA392, 0xFFD9A441, 0xFFB8361E, 0xFFB08BC9, 0xFF9FBF4A, 0xFF6FA3B8, 0xFFE07B2E, grit = 2f, shadow = 0xFF5A2A14, pad = "jar")
    val all = listOf(classic, specimen, stage, felt, rust)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: classic
}

/**
 * The button skin (the Look) for widgets that have no AppState at hand: classic, dark, light, or panel (the hardware
 * panel: bakelite keys in worn metal, Nov 21). AppState keeps it in step with its own look.
 */
object UiLook {
    var id by mutableStateOf("panel")
    val panel get() = id == "panel"
    /** the view size (70% and so on): panel keys keep a real 40 dp hit area at any view size */
    var scale by mutableStateOf(1f)
    /** the screen's width in the app's own dp (after the view size): keys grow with it and stop at a cap */
    var widthDp by mutableStateOf(400f)
    /** the lip under each key where its lamp sits */
    val lip = 8.dp
    /** Large keys (Settings, Look): every key about a quarter bigger, for playing at a small view size */
    var large by mutableStateOf(false)
    val big: Float get() = if (large) 1.25f else 1f
}

/** Keys that open a thing get their tile's colour on the cap (Nov 21); plain switches return null and stay dark. */
fun capTint(label: String): Color? {
    val w = label.replace(Regex("[^A-Za-z ]"), " ").trim().replace(Regex("\\s+"), " ")
    if (w.equals("Sample", true) || w.startsWith("Swap sound", true)) return tileColor("sound")
    val g = Icons.all.entries.firstOrNull { it.key.equals(w, ignoreCase = true) }?.value?.second ?: return null
    return when {
        g == "place" -> tileColor("place")
        w.equals("Edit", true) || w.equals("Pack", true) || w.equals("Menu", true) -> tileColor("perform")
        w.equals("FX", true) || w.equals("LFO", true) || w.startsWith("XY", true) || w.equals("Keys", true) -> tileColor("sound")
        else -> null
    }
}

/** Bakelite and worn metal, for the panel Look. */
object Hw {
    val Bake = Color(0xFF2B211C); val BakeTop = Color(0xFF3E3129); val BakeLow = Color(0xFF17110E)
    val Metal = Color(0xFF8E877D); val MetalHi = Color(0xFFC9C0B2); val MetalLow = Color(0xFF4E4943)
    val Engrave = Color(0xFFE6D8BC); val Brass = Color(0xFFC9A04A); val Oxblood = Color(0xFFA8321F)
    val Plate = Color(0xFF24211F); val PlateTop = Color(0xFF34302C)
    val Amber = Color(0xFFFFB347); val Glass = Color(0xFF10140F)
}

/** A readout window for the panel Look: dark glass sunk into a metal bezel, with a faint scanline glow. */
fun Modifier.lcd(lit: Boolean = false): Modifier = this.drawBehind {
    val r = CornerRadius(5.dp.toPx()); val bez = 2.5.dp.toPx()
    drawRoundRect(Brush.verticalGradient(listOf(Hw.MetalLow, Hw.Metal, Hw.MetalHi)), cornerRadius = r)
    drawRoundRect(if (lit) Color(0xFF1C2416) else Hw.Glass, topLeft = Offset(bez, bez), size = Size(size.width - bez * 2, size.height - bez * 2), cornerRadius = CornerRadius(3.dp.toPx()))
    var y = bez + 1f
    while (y < size.height - bez) { drawLine(Hw.Amber.copy(alpha = .035f), Offset(bez, y), Offset(size.width - bez, y), 1f); y += 3f }
    drawLine(Color.White.copy(alpha = .10f), Offset(bez + 2f, bez + 1.5f), Offset(size.width - bez - 2f, bez + 1.5f), 1.5f)
}

/**
 * A bakelite key in a worn metal bezel. Nov 21 video: the lamp sits in the metal lip under the cap, not on the cap, so
 * the word and icon stay centred; keys that only do something (no on or off) have no lamp at all.
 */
fun Modifier.bakelite(on: Boolean, lamp: Color, pressed: Boolean, danger: Boolean = false, brass: Boolean = false, lamped: Boolean = true, tint: Color? = null): Modifier = this.drawWithCache {
    // Nov 21 (lag): the brushes and scuffs are worked out once per size and state, not on every redraw of every key
    val r = CornerRadius(7.dp.toPx())
    val bez = 2.5.dp.toPx()
    val lip = UiLook.lip.toPx()
    val rim = Brush.verticalGradient(when { danger -> listOf(Color(0xFFC4573F), Hw.Oxblood, Color(0xFF5A1A10)); brass -> listOf(Color(0xFFE8C877), Hw.Brass, Color(0xFF6E5420)); else -> listOf(Hw.MetalHi, Hw.Metal, Hw.MetalLow) })
    // Nov 21: keys that open a thing wear their tile's colour in the bakelite (the 16-bit tiles Tyler liked); switches stay dark
    fun t(c: Color, k: Float) = if (tint == null) c else lerp(c, tint, k)
    val cap = Brush.verticalGradient(if (pressed) listOf(t(Hw.BakeLow, .35f), t(Hw.Bake, .45f)) else listOf(t(Hw.BakeTop, .6f), t(Hw.Bake, .5f), t(Hw.BakeLow, .4f)))
    val capR = CornerRadius(5.dp.toPx())
    val capH = size.height - bez - lip
    var seed = (size.width * 31 + size.height).toInt() * 1103515245 + 12345
    fun next(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }
    val scuffs = List(3) { val x = bez + next() * (size.width - bez * 2 - 8f); val y = bez + next() * (capH - 4f).coerceAtLeast(1f); floatArrayOf(x, y, x + 4f + next() * 14f, y + next() * 3f, .05f + next() * .05f) }
    val lr = 2.6.dp.toPx(); val lc = Offset(size.width / 2, size.height - lip / 2 - .5.dp.toPx())
    val glow = Brush.radialGradient(listOf(lamp.copy(alpha = .6f), Color.Transparent), center = lc, radius = lr * 5f)
    val shadowOff = Offset(0f, 2.5.dp.toPx())
    onDrawBehind {
        // shadow under the key, gone while pressed
        if (!pressed) drawRoundRect(Color.Black.copy(alpha = .55f), topLeft = shadowOff, size = size, cornerRadius = r)
        // the bezel: worn metal, brighter on the top edge, brass for the main key, oxblood for danger
        drawRoundRect(rim, cornerRadius = r)
        // the cap: bakelite, a soft dome of light from above, darker when pushed in; the lip below it stays metal
        drawRoundRect(cap, topLeft = Offset(bez, bez), size = Size(size.width - bez * 2, capH), cornerRadius = capR)
        // wear: a few pale scuffs on the cap, the same on every redraw for a given size
        for (sc in scuffs) drawLine(Color.White.copy(alpha = sc[4]), Offset(sc[0], sc[1]), Offset(sc[2], sc[3]), strokeWidth = 1f)
        // the lamp: a small glass bead set in the lip, centred under the cap, that glows when on
        if (lamped) {
            if (on) drawCircle(glow, radius = lr * 5f, center = lc)
            drawCircle(Color.Black.copy(alpha = .5f), radius = lr * 1.35f, center = lc)
            drawCircle(if (on) lamp else Color(0xFF0E0B09), radius = lr, center = lc)
            drawCircle(Color.White.copy(alpha = if (on) .55f else .18f), radius = lr * .35f, center = Offset(lc.x - lr * .3f, lc.y - lr * .35f))
        }
    }
}

/**
 * The close control: a cross-head screw in the corner of the panel. Tap it and the screw turns out a quarter, and
 * the panel comes off. An X with a reason to be there.
 */
@Composable
fun CloseScrew(onClick: () -> Unit, tag: String, modifier: Modifier = Modifier, label: String = "Close") {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val turn by androidx.compose.animation.core.animateFloatAsState(if (pressed) 90f else 0f, androidx.compose.animation.core.tween(160), label = "screw")
    Box(
        modifier.size((44f / UiLook.scale.coerceIn(.5f, 1.5f)).coerceAtLeast(36f).dp)
            .clickable(src, indication = null, enabled = true, onClickLabel = label) { onClick() }
            .tagged(tag)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val c = center; val rad = size.minDimension / 2
            drawCircle(Color.Black.copy(alpha = .5f), rad, Offset(c.x + 1.5f, c.y + 2.5f))
            drawCircle(Brush.radialGradient(listOf(Hw.MetalHi, Hw.Metal, Hw.MetalLow), center = Offset(c.x - rad * .35f, c.y - rad * .4f), radius = rad * 1.6f), rad, c)
            drawCircle(Hw.MetalLow, rad, c, style = Stroke(1.5f))
            // the cross slot, set at 45 degrees so it reads as an X, turning as you press
            val a = Math.toRadians((45f + turn).toDouble()); val len = rad * .62f; val w = rad * .26f
            for (k in 0..1) {
                val ang = a + k * Math.PI / 2
                val dx = (Math.cos(ang) * len).toFloat(); val dy = (Math.sin(ang) * len).toFloat()
                drawLine(Color(0xFF1A1613), Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(Color.White.copy(alpha = .25f), Offset(c.x - dx + 1f, c.y - dy + 1.5f), Offset(c.x + dx + 1f, c.y + dy + 1.5f), strokeWidth = 1f)
            }
        }
    }
}

/** The colours of the chosen skin. Every screen reads these, so a new skin changes the whole app at once. */
object C {
    var skin by mutableStateOf(Skins.classic)
    val Ink get() = Color(skin.ink)
    val Paper get() = Color(skin.paper)
    val Card get() = Color(skin.card)
    val Card2 get() = Color(skin.card2)
    val Empty get() = Color(skin.empty)
    val Cream get() = Color(skin.cream)
    val Dim get() = Color(skin.dim)
    val Pink get() = Color(skin.pink)
    val Teal get() = Color(skin.teal)
    val Yellow get() = Color(skin.yellow)
    val Riot get() = Color(skin.riot)
    val Purple get() = Color(skin.purple)
    val Lime get() = Color(skin.lime)
    val Black = Color(0xFF000000)
    /** The deepest panels (the BPM box, fader wells, lane strips): black on the dark skins, a darker paper on a light one */
    val Well get() = if (skin.light) Color(0xFFCFC5AE) else Black
    val Letters get() = listOf(Pink, Teal, Yellow, Purple, Color(skin.sky), Color(skin.orange), Lime, Riot)
}

val Archivo = FontFamily(
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_extrabold, FontWeight.ExtraBold),
    Font(R.font.archivo_black, FontWeight.Black),
)
val Bungee = FontFamily(Font(R.font.bungee))
val Mono = FontFamily(Font(R.font.plexmono_400, FontWeight.Normal), Font(R.font.plexmono_700, FontWeight.Bold))
/** The panel Look's type (Nov 21): worn rubber-stamp letters on the keys, a stencil for the tiny stamped words, an LCD face for readouts */
val Worn = FontFamily(Font(R.font.rubik_distressed))
val Stencil = FontFamily(Font(R.font.black_ops_one))
val Lcd = FontFamily(Font(R.font.share_tech_mono))

/** An aged enamel look for the inlaid icons: a little less colour, a little warmer, unless the key is lit. */
fun enamel(lit: Boolean): androidx.compose.ui.graphics.ColorFilter {
    val s = if (lit) 1.1f else .78f
    val lr = .2126f; val lg = .7152f; val lb = .0722f
    val m = floatArrayOf(
        lr * (1 - s) + s, lg * (1 - s), lb * (1 - s), 0f, if (lit) 10f else 6f,
        lr * (1 - s), lg * (1 - s) + s, lb * (1 - s), 0f, if (lit) 6f else 0f,
        lr * (1 - s), lg * (1 - s), lb * (1 - s) + s, 0f, if (lit) 0f else -12f,
        0f, 0f, 0f, 1f, 0f,
    )
    return androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(m))
}

/**
 * An icon from Tyler's drawings, inlaid in a key: drawn at a whole-number multiple of its 40 pixels so the pixel art
 * stays crisp (no uneven pixels), with a dark engraved shadow under it for depth.
 */
@Composable
fun InlayIcon(res: Int, maxDp: androidx.compose.ui.unit.Dp, lit: Boolean, label: String, modifier: Modifier = Modifier) {
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val px = with(dens) { maxDp.toPx() }
    // the nearest whole multiple (a 26 dp icon on a 2.6x screen is 68 px: 2x, 80 px, not 1x and half the size)
    var k = maxOf(1, Math.round(px / 40f))
    if (k > 1 && 40f * k > px * 1.15f) k--
    val side = with(dens) { (40f * k).toDp() }
    val img = androidx.compose.ui.graphics.ImageBitmap.imageResource(res)
    Box(modifier.size(side)) {
        androidx.compose.foundation.Image(img, null, Modifier.size(side).offset(1.dp, 1.5.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black.copy(alpha = .7f), androidx.compose.ui.graphics.BlendMode.SrcIn))
        androidx.compose.foundation.Image(img, label, Modifier.size(side), filterQuality = androidx.compose.ui.graphics.FilterQuality.None, colorFilter = enamel(lit))
    }
}

/** Words on a colour: whichever of the skin's dark and light reads better on it (on a light skin, Cream is the dark one). */
fun ink(on: Color): Color {
    val l = on.luminance()
    val a = C.Ink; val b = C.Cream
    fun contrast(x: Color): Float { val m = x.luminance(); return (maxOf(l, m) + .05f) / (minOf(l, m) + .05f) }
    return if (contrast(a) >= contrast(b)) a else b
}

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
            val sc = C.skin.shadow?.let { Color(it) } ?: shadowColor
            if (shadow > 0.dp && sc.alpha > 0f) drawRoundRect(sc, topLeft = Offset(shadow.toPx(), shadow.toPx()), size = size, cornerRadius = CornerRadius(radius.toPx()))
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

fun Modifier.grit(): Modifier = this.drawBehind { val g = C.skin.grit; if (g > 0f) drawRect(gritBrush(), alpha = g.coerceAtMost(1f)); if (g > 1f) drawRect(gritBrush(), topLeft = Offset(48f, 31f), alpha = g - 1f) }

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
    if (LocalHelp.current) Note(text, modifier.tagged("help"), C.Teal)
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
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.Ink, on: Boolean? = null,
    onColor: Color = C.Yellow, enabled: Boolean = true, small: Boolean = false, tag: String? = null, tier: Tier = Tier.Normal,
    tight: Boolean = false,
) {
    // on given (even false): a switch, with a lamp in the panel Look. Left out: a key that only does something, no lamp
    val lamped = on != null
    @Suppress("NAME_SHADOWING") val on = on == true
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    if (UiLook.panel) {
        // the hardware panel: a bakelite key, engraved words, a lamp under it for on. At least 40 real dp tall at any view size.
        // Keys widen with the screen and stop at a cap, so a wide screen has bigger keys, not lonely little ones
        val minH = maxOf(if (small) 34f else 44f, 40f / UiLook.scale.coerceIn(.4f, 2f)) * UiLook.big + UiLook.lip.value
        val minW = if (tight) 0f else (UiLook.widthDp * (if (small) .14f else .18f)).coerceIn(if (small) 48f else 56f, if (small) 120f else 150f) * UiLook.big
        Box(
            modifier
                .heightIn(min = minH.dp)
                .widthIn(min = minW.dp)
                .offset(0.dp, if (pressed && enabled) 2.dp else 0.dp)
                .bakelite(on, if (tier == Tier.Danger) Hw.Oxblood else onColor, pressed && enabled, danger = tier == Tier.Danger, brass = tier == Tier.Primary, lamped = lamped, tint = if (tight) null else capTint(text))
                .alpha(if (enabled) 1f else .4f)
                .clickable(src, indication = null, enabled = enabled) { onClick() }
                .then(if (tag != null) Modifier.tagged(tag) else Modifier)
                .semantics { selected = on }
                .padding(start = if (tight) 4.dp else 12.dp, end = if (tight) 4.dp else 12.dp, top = if (small) 5.dp else 7.dp, bottom = (if (small) 5.dp else 7.dp) + UiLook.lip),
            contentAlignment = Alignment.Center,
        ) {
            // Nov 21: worn rubber-stamp letters, and the icon from Tyler's drawings beside the word when the word has one,
            // as big as the cap allows
            val icon = if (tight) null else Icons.forLabel(text)
            // with the icon showing, the little symbol in the words (✂, ☆, •) goes: the icon says it better
            // (in the panel Look the old symbols go anyway: the worn letters have no ● or ✂, and the lamp says on)
            val words = text.replace(Regex("^[^A-Za-z0-9\\s]+\\s+"), "").ifEmpty { text }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) InlayIcon(icon, (if (small) 28.dp else 32.dp) * UiLook.big, on, text, Modifier.padding(end = 7.dp))
                T(words.uppercase(), color = when { tier == Tier.Quiet -> Hw.Engrave.copy(alpha = .6f); on -> lerp(Hw.Engrave, onColor, .35f); else -> Hw.Engrave },
                    size = (if (small) 12f else 13f).let { (it * UiLook.big).sp }, lines = 1, spacing = if (tight) 0.em else .03.em, family = Worn, weight = FontWeight.Normal)
            }
        }
        return
    }
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
            .then(if (tag != null) Modifier.tagged(tag) else Modifier)
            .semantics { selected = on }
            // tight: for buttons that share a row by weight, where 10 dp of padding each side cut the words short on narrow phones
            .padding(horizontal = if (tight) 4.dp else if (small) 10.dp else 14.dp, vertical = if (small) 6.dp else 9.dp),
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
    small: Boolean = true, color: Color = C.Ink, tag: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier, propagateMinConstraints = true) {
        Btn("$label ▾", { open = true }, small = small, color = color, enabled = !LocalLocked.current, tag = tag)
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
        Column(Modifier.fillMaxSize().verticalScroll(ss, enabled = !locked).tagged(tag).padding(start = hPad, top = vPad, bottom = vPad + 24.dp, end = hPad + 14.dp), content = content)
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
            .tagged("scrollbar")
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
            .tagged("scrollbar")
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
            DropdownMenuItem(text = { T(label.uppercase(), size = 13.sp, spacing = .06.em) }, onClick = { onClose(); act() }, modifier = Modifier.tagged(tag))
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
