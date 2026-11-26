package com.tideclock.messmachine.ui

import androidx.compose.runtime.Composable

/** The screen's width in dp, read again when it changes (a Fold opening, a turn). Android: the Configuration. */
@Composable
expect fun screenWidthDp(): Int
