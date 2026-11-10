package com.tideclock.messmachine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.R

/** The site's palette, dark lab version. */
object C {
    val Ink = Color(0xFF18161A)
    val Paper = Color(0xFF141215)
    val Card = Color(0xFF211D24)
    val Card2 = Color(0xFF2E2833)
    val Empty = Color(0xFF3A3440)
    val Cream = Color(0xFFFFF4E2)
    val Dim = Color(0xFFB8AC9E)
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

fun ink(on: Color): Color = if (on.luminance() > .45f) C.Ink else C.Cream

fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/** Roll colours by pitch class: teal to pink to yellow to riot red, like the site's paint palette. */
fun pcColor(pc: Int): Color {
    val k = ((pc % 12) + 12) % 12
    return when {
        k < 4 -> lerp(C.Teal, C.Pink, k / 4f)
        k < 8 -> lerp(C.Pink, C.Yellow, (k - 4) / 4f)
        else -> lerp(C.Yellow, C.Riot, (k - 8) / 4f)
    }
}

/** Rounded sticker with a hard black shadow, the site's chunky look. */
fun Modifier.sticker(color: Color, radius: Dp = 12.dp, shadow: Dp = 3.dp, border: Dp = 2.dp, borderColor: Color = C.Black): Modifier =
    this
        .drawBehind {
            if (shadow > 0.dp) drawRoundRect(C.Black, topLeft = Offset(shadow.toPx(), shadow.toPx()), size = size, cornerRadius = CornerRadius(radius.toPx()))
        }
        .background(color, RoundedCornerShape(radius))
        .border(border, borderColor, RoundedCornerShape(radius))

@Composable
fun T(
    text: String, modifier: Modifier = Modifier, color: Color = C.Cream, size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.ExtraBold, lines: Int = Int.MAX_VALUE, family: FontFamily = Archivo,
) {
    Text(
        text, modifier = modifier, color = color, maxLines = lines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = family, fontWeight = weight, fontSize = size, lineHeight = size * 1.2f)
    )
}

@Composable
fun Btn(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.Card2, on: Boolean = false,
    onColor: Color = C.Yellow, enabled: Boolean = true, small: Boolean = false,
) {
    val bg = if (on) onColor else color
    Box(
        modifier
            .heightIn(min = if (small) 32.dp else 42.dp)
            .sticker(bg, radius = 10.dp, shadow = if (on) 1.dp else 3.dp)
            .alpha(if (enabled) 1f else .4f)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = if (small) 9.dp else 12.dp, vertical = if (small) 5.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, color = ink(bg), size = if (small) 12.sp else 14.sp, lines = 1)
    }
}

/** A button that opens a short list. */
@Composable
fun <V> Select(
    label: String, options: List<Pair<V, String>>, onPick: (V) -> Unit, modifier: Modifier = Modifier,
    small: Boolean = true, color: Color = C.Card2,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Btn("$label ▾", { open = true }, small = small, color = color)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(C.Card)) {
            for ((v, name) in options) {
                DropdownMenuItem(
                    text = { T(name, size = 14.sp, weight = FontWeight.Medium) },
                    onClick = { open = false; onPick(v) },
                )
            }
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, color: Color = C.Card, content: @Composable () -> Unit) {
    Box(modifier.sticker(color, radius = 14.dp).padding(12.dp)) { content() }
}

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, show: (Float) -> String,
              onChange: (Float) -> Unit, onDone: () -> Unit, steps: Int = 0, accent: Color = C.Pink) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(label, size = 13.sp, modifier = Modifier.weight(1f))
            T(show(value), size = 13.sp, color = C.Dim, weight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            onValueChangeFinished = onDone, steps = steps,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = C.Card2),
        )
    }
}

@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
