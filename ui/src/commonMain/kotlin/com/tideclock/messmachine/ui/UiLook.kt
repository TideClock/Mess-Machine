package com.tideclock.messmachine.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

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
