package com.tideclock.messmachine

import android.content.ContentProvider
import android.content.pm.ProviderInfo

/**
 * The app under Robolectric (robolectric.properties). Robolectric does not start the content providers in the
 * manifest, and Compose Multiplatform's resources (the icons and fonts in ui/composeResources, CMP step 2) learn the
 * app's context from one of them. On a phone Android starts it before the app; here this does.
 */
class TestMessApp : MessApp() {
    override fun onCreate() {
        val p = Class.forName("org.jetbrains.compose.resources.AndroidContextProvider").getDeclaredConstructor().newInstance() as ContentProvider
        p.attachInfo(this, ProviderInfo().apply { authority = "$packageName.resources.AndroidContextProvider" })
        super.onCreate()
    }
}
