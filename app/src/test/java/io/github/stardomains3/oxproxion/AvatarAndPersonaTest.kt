package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, sdk = [35])
class AvatarAndPersonaTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun solid(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(color) }

    /** The persona card's portrait must come out at the photo's own colors, not tinted by the base fill. */
    @Test fun personaTilePhotoIsNotWashedOut() {
        val art = RpTileArt(ctx, RpTileArt.Kind.PERSONA, Color.WHITE, Color.DKGRAY).apply { photo = solid(64, 64, Color.RED) }
        art.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        art.layout(0, 0, 300, 300)
        val out = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        art.draw(Canvas(out))
        val px = out.getPixel(150, (300 * 0.635f).toInt())
        assertEquals(255, Color.red(px))
        assertTrue("green ${Color.green(px)}", Color.green(px) < 8)
        assertTrue("blue ${Color.blue(px)}", Color.blue(px) < 8)
    }

    /** The crop is what the window shows: a wide photo's middle, at full opacity, square. */
    @Test fun cropReturnsTheWindowAsASquare() {
        val photo = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            c.drawColor(Color.BLUE)
            val p = android.graphics.Paint().apply { color = Color.GREEN }
            c.drawRect(150f, 0f, 250f, 200f, p)
        }
        val view = AvatarCropView(ctx)
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 600, 800)
        view.setImage(photo)
        val cut = view.crop(128)
        assertNotNull(cut)
        assertEquals(128, cut!!.width)
        assertEquals(128, cut.height)
        // The window covers the photo's central 200x200; the green stripe is its middle half.
        assertEquals(Color.GREEN, cut.getPixel(64, 64))
        assertEquals(Color.BLUE, cut.getPixel(4, 64))
        assertEquals(255, Color.alpha(cut.getPixel(64, 4)))
    }

    @Test fun personaCanBeSwitchedOffWithoutLosingIt() {
        val prefs = SharedPreferencesHelper(ctx)
        prefs.saveRpPersona("A tall stranger")
        prefs.saveRpPersonaName("Lilith")
        assertTrue(prefs.isRpPersonaEnabled())
        assertEquals("Lilith", prefs.activeRpPersonaName())
        prefs.setRpPersonaEnabled(false)
        assertFalse(prefs.isRpPersonaEnabled())
        assertEquals("", prefs.activeRpPersona())
        assertEquals("", prefs.activeRpPersonaName())
        // Still saved for when it comes back.
        assertEquals("A tall stranger", prefs.getRpPersona())
        assertEquals("Lilith", prefs.getRpPersonaName())
        prefs.setRpPersonaEnabled(true)
        assertEquals("A tall stranger", prefs.activeRpPersona())
    }

    /** The crop screen must inflate: the crop view needs the (Context, AttributeSet) constructor. */
    @Test fun pickerLayoutsInflate() {
        val themed = android.view.ContextThemeWrapper(ctx, R.style.Theme_Grokion)
        val crop = android.view.LayoutInflater.from(themed).inflate(R.layout.dialog_avatar_crop, null)
        assertNotNull(crop.findViewById<AvatarCropView>(R.id.avatarCropView))
    }

    /** Deleting a character must not leave its notes, layout, voice, pinned book or wallpaper behind. */
    @Test fun deletingACharacterClearsItsPrefsAndWallpaper() {
        val prefs = SharedPreferencesHelper(ctx)
        val id = 4242L
        prefs.saveRpMemory(id, "note")
        prefs.saveRpLayout(id, SharedPreferencesHelper.RP_LAYOUT_BOOK)
        prefs.saveRpVoice(id, SharedPreferencesHelper.RpVoice("voice-a", 1.2f, 0.85f))
        prefs.saveRpLorebookId(id, 7L)
        val wallpaper = BackgroundPhoto.file(ctx, BackgroundPhoto.slotForCharacter(id)).apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        prefs.clearRpCharacterPrefs(id)
        assertEquals("", prefs.getRpMemory(id))
        assertEquals(SharedPreferencesHelper.RP_LAYOUT_CLASSIC, prefs.getRpLayout(id))
        assertEquals(SharedPreferencesHelper.RpVoice(null, 1f, 1f), prefs.getRpVoice(id))
        assertEquals(null, prefs.getRpLorebookId(id))
        assertFalse(wallpaper.exists())
    }

    /** The character library and the chats page host a ⋮ menu, so each has a frame and a backdrop for its glass. */
    @Test fun rpScreensInflateWithTheirMenuHostsAndNewPieces() {
        val themed = android.view.ContextThemeWrapper(ctx, R.style.Theme_Grokion)
        val inflater = android.view.LayoutInflater.from(themed)
        val library = inflater.inflate(R.layout.fragment_rp_character_library, null)
        assertTrue(library is android.widget.FrameLayout)
        assertNotNull(library.findViewById<View>(R.id.rpLibraryBackdrop))
        assertNotNull(library.findViewById<View>(R.id.rpCharacterRecyclerView))
        val history = inflater.inflate(R.layout.fragment_rp_chat_history, null)
        assertTrue(history is android.widget.FrameLayout)
        assertNotNull(history.findViewById<View>(R.id.rpHistoryBackdrop))
        assertNotNull(history.findViewById<View>(R.id.rpHistoryList))
        assertNotNull(inflater.inflate(R.layout.item_rp_history_chat, null).findViewById<View>(R.id.rpHistoryMore))
        assertNotNull(inflater.inflate(R.layout.view_rp_home, null).findViewById<View>(R.id.rpHomeEmptyAdd))
    }

    /** The panel's full-screen pages are built on these two shells. */
    @Test fun panelPageLayoutsInflate() {
        val themed = android.view.ContextThemeWrapper(ctx, R.style.Theme_Grokion)
        val inflater = android.view.LayoutInflater.from(themed)
        val page = inflater.inflate(R.layout.fragment_rp_page, null)
        assertNotNull(page.findViewById<View>(R.id.rpPageBody))
        assertNotNull(page.findViewById<View>(R.id.rpPageScroll))
        val voice = inflater.inflate(R.layout.fragment_rp_voice, null)
        assertNotNull(voice.findViewById<View>(R.id.rpPageScroll))
        assertNotNull(voice.findViewById<View>(R.id.rpVoiceList))
        assertNotNull(voice.findViewById<View>(R.id.rpVoicePitch))
    }
}
