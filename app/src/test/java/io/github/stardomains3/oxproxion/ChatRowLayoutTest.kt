package io.github.stardomains3.oxproxion

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/** The history row and the user bubble, inflated the way the adapters see them. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ChatRowLayoutTest {
    private fun inflate(layout: Int): View {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_Grokion,
        )
        return LayoutInflater.from(ctx).inflate(layout, null, false)
    }

    @Test fun show_more_sits_outside_the_hidden_action_row() {
        val row = inflate(R.layout.item_message_user)
        val expand = row.findViewById<TextView>(R.id.collapseToggleButton)
        val actions = row.findViewById<ViewGroup>(R.id.buttonContainer)
        assertNotNull(expand)
        assertFalse(expand.parent === actions)
        assertEquals(View.GONE, expand.visibility)
        assertEquals(expand.context.getString(R.string.cd_show_more), expand.text.toString())
        val density = row.resources.displayMetrics.density
        assertEquals(44, (expand.layoutParams.height / density).roundToInt())
        assertTrue(expand.textSize / density >= 13f)
    }

    @Test fun history_row_pin_starts_hidden() {
        val row = inflate(R.layout.item_saved_chat)
        assertEquals(View.GONE, row.findViewById<View>(R.id.savedChatPin).visibility)
    }
}
