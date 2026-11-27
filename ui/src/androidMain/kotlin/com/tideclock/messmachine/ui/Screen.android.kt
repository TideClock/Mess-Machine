package com.tideclock.messmachine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
actual fun screenWidthDp(): Int = LocalConfiguration.current.screenWidthDp
