package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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

/** Square, uppercase, cream outline, pink shadow; it sinks into its shadow when pressed. */
@Composable
fun Btn(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.Ink, on: Boolean = false,
    onColor: Color = C.Yellow, enabled: Boolean = true, small: Boolean = false, tag: String? = null,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg = if (on) onColor else color
    val sh = if (small) 3.dp else 4.dp
    val sink = if (pressed && enabled) sh else if (on) 1.dp else 0.dp
    Box(
        modifier
            .heightIn(min = if (small) 34.dp else 44.dp)
            .offset(sink, sink)
            .sticker(bg, shadow = sh - sink, border = if (small) 2.5.dp else 3.dp)
            .alpha(if (enabled) 1f else .38f)
            .clickable(src, indication = null, enabled = enabled) { onClick() }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = if (small) 10.dp else 14.dp, vertical = if (small) 6.dp else 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text.uppercase(), color = ink(bg), size = if (small) 12.sp else 13.sp, lines = 1, spacing = .08.em)
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
        Btn("$label ▾", { open = true }, small = small, color = color)
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

/** Buttons that wrap onto a new line instead of hiding off the right edge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Wrap(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
