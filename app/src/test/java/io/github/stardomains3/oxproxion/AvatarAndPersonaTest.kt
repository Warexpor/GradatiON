package io.github.stardomains3.oxproxion

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.util.Base64
import android.view.View
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** A camera JPEG is stored on its side. The portrait written for a character has to be upright. */
    @Test fun aSidewaysPortraitIsStoredUpright() {
        val wide = Bitmap.createBitmap(24, 8, Bitmap.Config.ARGB_8888)
        wide.eraseColor(Color.DKGRAY)
        val raw = ByteArrayOutputStream().also { wide.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        wide.recycle()
        val src = File(ctx.cacheDir, "sideways-portrait.jpg")
        src.writeBytes(raw)
        ExifInterface(src.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val encoded = Base64.encodeToString(src.readBytes(), Base64.NO_WRAP)
        val saved = RpAvatarStorage.saveFromBase64(ctx, encoded, 42L)
        assertNotNull(saved)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(RpAvatarStorage.avatarFile(ctx, 42L).absolutePath, bounds)
        assertEquals(8, bounds.outWidth)
        assertEquals(24, bounds.outHeight)
        val kept = RpAvatarStorage.avatarFile(ctx, 42L).readBytes()
        assertNull(RpAvatarStorage.saveFromBase64(ctx, "!!!!", 42L))
        assertTrue(kept.contentEquals(RpAvatarStorage.avatarFile(ctx, 42L).readBytes()))
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

    /** A kill after the side files are gone must not be able to put the portrait back. */
    @Test fun aKilledAvatarDeleteDropsTheSideFileFirst() {
        val id = 81L
        val file = RpAvatarStorage.avatarFile(ctx, id)
        file.parentFile?.mkdirs()
        val live = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
        val side = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02, 0xFF.toByte(), 0xD9.toByte())
        file.writeBytes(live)
        val partial = File(file.parentFile, "${file.name}.partial")
        partial.writeBytes(side)
        partial.setLastModified(file.lastModified() + 5_000)
        ScenePhoto.stopAfterSidesForTest = true
        try {
            RpAvatarStorage.deleteAvatar(ctx, id)
            assertFalse(partial.exists())
            assertTrue(live.contentEquals(file.readBytes()))
            assertTrue(RpAvatarStorage.hasAvatar(ctx, id))
            assertTrue(live.contentEquals(file.readBytes()))
        } finally {
            ScenePhoto.stopAfterSidesForTest = false
            RpAvatarStorage.deleteAvatar(ctx, id)
        }
    }

    /** Delete must drop .bak / .partial too: recover would otherwise put a removed portrait back. */
    @Test fun deleteAvatarClearsSideFilesSoTheyCannotResurrect() {
        val id = 77L
        val file = RpAvatarStorage.avatarFile(ctx, id)
        file.parentFile?.mkdirs()
        val jpeg = ByteArrayOutputStream().also {
            solid(16, 16, Color.GRAY).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        assertTrue(ScenePhoto.completeJpeg(jpeg))
        file.writeBytes(jpeg)
        File(file.parentFile, "${file.name}.bak").writeBytes(jpeg)
        File(file.parentFile, "${file.name}.partial").writeBytes(jpeg)
        assertTrue(RpAvatarStorage.hasAvatar(ctx, id))
        assertNotNull(RpAvatarStorage.encodeAvatarBase64(ctx, id))
        RpAvatarStorage.deleteAvatar(ctx, id)
        assertFalse(file.exists())
        assertFalse(File(file.parentFile, "${file.name}.bak").exists())
        assertFalse(File(file.parentFile, "${file.name}.partial").exists())
        assertFalse(RpAvatarStorage.hasAvatar(ctx, id))
        // No file exports as empty, so a backup can clear the portrait. Null is a torn file.
        assertEquals("", RpAvatarStorage.encodeAvatarBase64(ctx, id))
    }

    /** A torn file at the portrait name is not exported or shown as a picture. */
    @Test fun tornAvatarIsNotAPicture() {
        val id = 78L
        val file = RpAvatarStorage.avatarFile(ctx, id)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x00))
        assertFalse(RpAvatarStorage.hasAvatar(ctx, id))
        assertNull(RpAvatarStorage.encodeAvatarBase64(ctx, id))
    }

    /** A finished .bak beside a torn portrait is the picture, until delete removes both. */
    @Test fun tornAvatarRecoversFromBakUntilDelete() {
        val id = 79L
        val file = RpAvatarStorage.avatarFile(ctx, id)
        file.parentFile?.mkdirs()
        val jpeg = ByteArrayOutputStream().also {
            solid(16, 16, Color.BLUE).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x00))
        val bak = File(file.parentFile, "${file.name}.bak")
        bak.writeBytes(jpeg)
        assertTrue(RpAvatarStorage.hasAvatar(ctx, id))
        assertTrue(ScenePhoto.completeJpeg(file))
        assertNotNull(RpAvatarStorage.encodeAvatarBase64(ctx, id))
        RpAvatarStorage.deleteAvatar(ctx, id)
        assertFalse(file.exists())
        assertFalse(bak.exists())
        assertFalse(RpAvatarStorage.hasAvatar(ctx, id))
    }

    /** A torn persona file is not a portrait, and prune drops side files nothing keeps. */
    @Test fun tornPersonaIsMissingAndPruneDropsSideFiles() {
        val dir = File(ctx.filesDir, "rp_persona_avatars").apply { mkdirs() }
        val keep = "persona_keep.jpg"
        val drop = "persona_drop.jpg"
        val jpeg = ByteArrayOutputStream().also {
            solid(16, 16, Color.GREEN).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        File(dir, keep).writeBytes(jpeg)
        File(dir, "$keep.bak").writeBytes(jpeg)
        File(dir, "$keep.partial.incoming").writeBytes(jpeg)
        // Older than the live file, so opening the portrait does not install it and drop the bak.
        File(dir, keep).setLastModified(5_000)
        File(dir, "$keep.partial.incoming").setLastModified(1_000)
        File(dir, drop).writeBytes(jpeg)
        File(dir, "$drop.bak").writeBytes(jpeg)
        File(dir, "$drop.partial").writeBytes(jpeg)
        File(dir, "$drop.partial.incoming").writeBytes(jpeg)
        val torn = "persona_torn.jpg"
        File(dir, torn).writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x00))
        assertTrue(RpAvatarStorage.hasPersonaPhoto(ctx, keep))
        assertFalse(RpAvatarStorage.hasPersonaPhoto(ctx, torn))
        RpAvatarStorage.prunePersonas(ctx, setOf(keep))
        assertTrue(File(dir, keep).isFile)
        assertTrue(File(dir, "$keep.bak").isFile)
        assertTrue(File(dir, "$keep.partial.incoming").isFile)
        assertFalse(File(dir, drop).exists())
        assertFalse(File(dir, "$drop.bak").exists())
        assertFalse(File(dir, "$drop.partial").exists())
        assertFalse(File(dir, "$drop.partial.incoming").exists())
        assertFalse(File(dir, torn).exists())
    }

    /** photoUri names the avatar file; a torn JPEG must not skip the completeness check. */
    @Test fun tornPhotoUriIsNotShownAsAPortrait() {
        val id = 80L
        val file = RpAvatarStorage.avatarFile(ctx, id)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x00))
        val character = RpCharacter(
            id = id,
            name = "Mira",
            photoUri = Uri.fromFile(file).toString(),
        )
        assertFalse(RpAvatarStorage.hasAvatar(ctx, id))
        assertNull(RpAvatars.photoModel(android.view.View(ctx), character))
    }

    /** Persona Save commits name/about/photo so a kill after the tap cannot drop them. */
    @Test fun personaSaveKeepsNameAboutAndPhoto() {
        val prefs = SharedPreferencesHelper(ctx)
        prefs.saveRpPersona("A tall stranger")
        prefs.saveRpPersonaName("Lilith")
        prefs.saveRpPersonaPhoto("persona_lilith.jpg")
        assertEquals("A tall stranger", prefs.getRpPersona())
        assertEquals("Lilith", prefs.getRpPersonaName())
        assertEquals("persona_lilith.jpg", prefs.getRpPersonaPhoto())
        prefs.saveRpPersonaPhoto(null)
        assertNull(prefs.getRpPersonaPhoto())
    }


    /** Opening a character commits the id, so a kill cannot reopen the previous one. */
    @Test fun activeCharacterIdIsKept() {
        val prefs = SharedPreferencesHelper(ctx)
        prefs.saveRpActiveCharacterId(42L)
        assertEquals(42L, SharedPreferencesHelper(ctx).getRpActiveCharacterId())
        prefs.saveRpActiveCharacterId(null)
        assertNull(SharedPreferencesHelper(ctx).getRpActiveCharacterId())
    }

}
