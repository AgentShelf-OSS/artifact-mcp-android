package ai.agentshelf.artifactmcp.ui

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Edge-to-edge helpers. Edge-to-edge is enforced for apps that target API 35 or later. */
internal object SystemBars {
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Dark status and navigation bar icons on a light theme, light icons on a dark theme. */
    fun matchIcons(window: Window, view: View, night: Boolean) {
        // The app paints its own colour behind the navigation bar. Without this, three-button
        // navigation gets a system scrim that keeps the theme of the first layout (light in dark mode).
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    /**
     * Keep [root] of an activity with an AppCompat action bar clear of the system bars, the display
     * cutout and the action bar. Edge-to-edge lays the content out from the top of the window, so
     * without this the first rows hide under the bar. AppCompat's decor already adds the action bar
     * height to the top inset it passes to the content, so the top inset is used as it is.
     * Call after setContentView (the window insets controller needs the decor view).
     */
    fun padBelowActionBar(activity: AppCompatActivity, root: View, includeIme: Boolean) {
        var types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        if (includeIme) types = types or WindowInsetsCompat.Type.ime()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(types)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        matchIcons(activity.window, root, isNight(activity))
    }
}
