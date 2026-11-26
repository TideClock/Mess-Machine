package com.tideclock.messmachine

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.tideclock.messmachine.platform.Platform
import com.tideclock.messmachine.ui.MessMachineApp
import platform.UIKit.UIViewController

/**
 * The iPhone's way in (CMP step 7): the whole app, the same screens and AppState as Android, in one view controller.
 * The iOS app makes a Platform (IosPlatform plus its Swift sound out, mic and pickers) and shows what this returns.
 */
fun MainViewController(platform: Platform): UIViewController = ComposeUIViewController {
    val state = remember { AppState(platform) }
    MessMachineApp(state)
}
