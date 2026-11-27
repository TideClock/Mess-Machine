package com.tideclock.messmachine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun screenWidthDp(): Int = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
