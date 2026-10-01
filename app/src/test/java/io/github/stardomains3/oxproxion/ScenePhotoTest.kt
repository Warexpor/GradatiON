package io.github.stardomains3.oxproxion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ScenePhotoTest {
    @Before fun resetFileProviderCache() {
        // FileProvider remembers the first test's files directory and then rejects the next one.
        val cache = FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (cache.get(null) as MutableMap<Any, Any>).clear()
    }

    @Test fun encodeShrinksAWidePicture() {
        val bmp = Bitmap.createBitmap(2000, 80, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.DKGRAY)
        val raw = ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bmp.recycle()
        val jpeg = ScenePhoto.encode(raw)
        assertNotNull(jpeg)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size, bounds)
        assertTrue("width ${bounds.outWidth}", bounds.outWidth in 1..ScenePhoto.MAX_EDGE)
        assertTrue("height ${bounds.outHeight}", bounds.outHeight in 1..ScenePhoto.MAX_EDGE)
    }

    @Test fun aDataUrlRoundTripsAndAFileUriIsKeptForEdit() {
        val payload = Base64.encodeToString(byteArrayOf(1, 2, 3, 4), Base64.NO_WRAP)
        val url = "data:image/png;base64,$payload"
        val staged = ScenePhoto.stagedFromDataUrl(url)
        assertNotNull(staged)
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(staged!!.bytes))
        assertEquals("image/png", staged.mime)
        assertNull(ScenePhoto.stagedFromDataUrl("data:text/plain;base64,$payload"))
        assertNull(ScenePhoto.editPhoto(null, "data:image/jpeg;base64,$payload"))
        val edit = ScenePhoto.editPhoto(url, "content://scene/1")
        assertEquals(url, edit?.dataUrl)
        assertEquals("content://scene/1", edit?.fileUri)
    }

    @Test fun aPictureWeOwnStaysPutAndAMissingOneIsRebuilt() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val jpeg = tinyJpeg()
        val dir = File(context.filesDir, "scene_photos").apply { mkdirs() }
        val file = File(dir, "kept.jpg").apply { writeBytes(jpeg) }
        val uri = "content://${context.packageName}.fileprovider/owned/scene_photos/kept.jpg"
        assertEquals(file.canonicalFile, ScenePhoto.ownedFile(context, uri)?.canonicalFile)
        assertEquals(uri, ScenePhoto.settle(context, uri, null))
        assertTrue(ScenePhoto.storedFile(context, uri))
        val cacheLink = "content://${context.packageName}.fileprovider/temp_images/scene_photos/kept.jpg"
        assertFalse(ScenePhoto.storedFile(context, cacheLink))
        file.writeBytes(jpeg.copyOf(4))
        assertFalse(ScenePhoto.storedFile(context, uri))
        assertNull(ScenePhoto.ownedFile(context, "content://${context.packageName}.fileprovider/owned/../kept.jpg"))

        file.delete()
        assertFalse(ScenePhoto.canRead(context, uri))
        val rebuilt = ScenePhoto.settle(context, uri, jpeg)
        assertNotNull(rebuilt)
        assertTrue(rebuilt!!.contains("/owned/scene_photos/"))
        val restored = ScenePhoto.ownedFile(context, rebuilt)
        assertNotNull(restored)
        assertTrue(restored!!.isFile)
        assertTrue(restored.length() > 0)

        val cache = File(File(context.cacheDir, "scene_photos").apply { mkdirs() }, "old.jpg")
        cache.writeBytes(jpeg)
        val cacheUri = "content://${context.packageName}.fileprovider/temp_images/scene_photos/old.jpg"
        val moved = ScenePhoto.settle(context, cacheUri, null)
        assertNotNull(moved)
        assertTrue(moved!!.contains("/owned/"))
        assertFalse(moved.contains("/temp_images/"))
    }

    @Test fun aDetailedPictureIsShrunkUntilItFits() {
        val bmp = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(1)
        val pixels = IntArray(bmp.width * bmp.height) { rnd.nextInt() }
        bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val raw = ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bmp.recycle()
        val jpeg = ScenePhoto.encode(raw, maxEdge = 320, maxBytes = 6_000)
        assertNotNull(jpeg)
        assertTrue("size ${jpeg!!.size}", jpeg.size <= 6_000)
        assertTrue(ScenePhoto.completeJpeg(jpeg))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        assertTrue(bounds.outWidth in 1..320)
        assertTrue(bounds.outHeight in 1..320)
    }

    @Test fun aRoleplaySendKeepsTheFileItCaptured() {
        val captured = "content://app.fileprovider/owned/scene_photos/11111111-1111-1111-1111-111111111111.jpg"
        assertEquals(captured, ScenePhoto.uriForTurn(useCaptured = true, captured = captured, live = null))
        assertNull(ScenePhoto.uriForTurn(useCaptured = true, captured = null, live = captured))
        assertEquals("content://live", ScenePhoto.uriForTurn(useCaptured = false, captured = captured, live = "content://live"))
        assertNull(ScenePhoto.uriForTurn(useCaptured = true, captured = "data:image/jpeg;base64,qq", live = null))
        assertNull(ScenePhoto.uriForTurn(useCaptured = true, captured = "  ", live = captured))
    }

    @Test fun aSceneFileNameIsTheUuidAndNothingElse() {
        val name = "11111111-1111-1111-1111-111111111111.jpg"
        val uri = "content://app.fileprovider/owned/scene_photos/$name"
        assertEquals(name, ScenePhoto.sceneFileName(uri))
        assertEquals(name, ScenePhoto.fileNameIn("caption scene_photos/notes then $uri tail"))
        val other = "22222222-2222-2222-2222-222222222222.jpg"
        val both = "$uri and content://app.fileprovider/owned/scene_photos/$other"
        assertEquals(listOf(name, other), ScenePhoto.fileNamesIn(both))
        assertEquals(name, ScenePhoto.fileNameIn(both))
        assertNull(ScenePhoto.sceneFileName("content://app.fileprovider/temp_images/scene_photos/$name"))
        assertNull(ScenePhoto.fileNameIn("scene_photos/not-a-uuid.jpg"))
        assertFalse(ScenePhoto.isSceneFileName("../$name"))
        assertFalse(ScenePhoto.isSceneFileName("notes.jpg"))
    }

    @Test fun anEditKeepsThePictureTheSaveWouldDrop() {
        val name = "22222222-2222-2222-2222-222222222222.jpg"
        val other = "33333333-3333-3333-3333-333333333333.jpg"
        val dropped = listOf(name, other)
        val held = ScenePhoto.scenePhotosSafeToDelete(dropped, heldForEdit = setOf(name), pendingName = null)
        assertEquals(listOf(other), held)
        val staged = ScenePhoto.scenePhotosSafeToDelete(
            dropped,
            heldForEdit = emptySet(),
            pendingName = name,
        )
        assertEquals(listOf(other), staged)
        assertEquals(
            listOf(other),
            ScenePhoto.scenePhotosSafeToDelete(dropped, heldForEdit = setOf(name), pendingName = "not-a-file"),
        )
        assertTrue(ScenePhoto.scenePhotosSafeToDelete(emptyList(), emptySet(), null).isEmpty())
    }

    @Test fun deleteSceneFilesRemovesOnlyThatName() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "22222222-2222-2222-2222-222222222222.jpg"
        val dir = File(context.filesDir, "scene_photos").apply { mkdirs() }
        val file = File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val other = File(dir, "33333333-3333-3333-3333-333333333333.jpg").apply { writeBytes(byteArrayOf(4)) }
        val sneaky = File(dir, "notes.jpg").apply { writeBytes(byteArrayOf(5)) }
        ScenePhoto.deleteSceneFiles(context, listOf(name, "../$name", "notes.jpg"))
        assertFalse(file.exists())
        assertTrue(other.exists())
        assertTrue(sneaky.exists())
    }

    @Test fun aTruncatedPhotoIsRebuiltFromTheMessage() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val jpeg = tinyJpeg()
        assertTrue(ScenePhoto.completeJpeg(jpeg))
        val dir = File(context.filesDir, "scene_photos").apply { mkdirs() }
        val torn = File(dir, "torn.jpg")
        torn.writeBytes(jpeg.copyOf(jpeg.size / 2))
        val uri = "content://${context.packageName}.fileprovider/owned/scene_photos/torn.jpg"
        assertFalse(ScenePhoto.completeJpeg(torn))
        assertFalse(ScenePhoto.canRead(context, uri))
        val rebuilt = ScenePhoto.settle(context, uri, jpeg)
        assertNotNull(rebuilt)
        assertTrue(rebuilt != uri)
        val restored = ScenePhoto.ownedFile(context, rebuilt!!)
        assertNotNull(restored)
        assertTrue(ScenePhoto.completeJpeg(restored!!))
        assertFalse(File(dir, "torn.jpg.partial").exists())
    }

    @Test fun anAtomicWriteLeavesNoPartialFile() {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "atomic-photo")
        dir.mkdirs()
        val dest = File(dir, "done.jpg")
        val jpeg = tinyJpeg()
        ScenePhoto.writeAtomically(dest, jpeg)
        assertTrue(ScenePhoto.completeJpeg(dest))
        assertFalse(File(dir, "done.jpg.partial").exists())
        assertTrue(jpeg.contentEquals(dest.readBytes()))
        val again = tinyJpeg()
        ScenePhoto.writeAtomically(dest, again)
        assertTrue(again.contentEquals(dest.readBytes()))
        assertFalse(File(dir, "done.jpg.partial").exists())
    }

    @Test fun aFailedRenameLeavesTheFinishedPicture() {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "atomic-keep")
        dir.mkdirs()
        val dest = File(dir, "keep.jpg")
        val original = tinyJpeg()
        dest.writeBytes(original)
        val replacement = ByteArray(original.size) { index ->
            if (index == original.size / 2) 0x11 else original[index]
        }
        ScenePhoto.failNextRenameForTest()
        try {
            ScenePhoto.writeAtomically(dest, replacement)
            throw AssertionError("replace should fail when the finished file cannot be renamed over")
        } catch (_: java.io.IOException) {
        }
        assertTrue(original.contentEquals(dest.readBytes()))
        assertTrue(ScenePhoto.completeJpeg(dest))
        assertFalse(File(dir, "keep.jpg.partial").exists())
    }

    @Test fun aKilledReplaceIsRestoredFromTheSideFile() {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "atomic-recover")
        dir.mkdirs()
        val dest = File(dir, "wall.jpg")
        val jpeg = tinyJpeg()
        val partial = File(dir, "wall.jpg.partial")
        partial.writeBytes(jpeg)
        assertFalse(dest.exists())
        assertTrue(ScenePhoto.recover(dest))
        assertTrue(jpeg.contentEquals(dest.readBytes()))
        assertFalse(partial.exists())

        dest.writeBytes(jpeg.copyOf(4))
        val bak = File(dir, "wall.jpg.bak")
        bak.writeBytes(jpeg)
        assertTrue(ScenePhoto.recover(dest))
        assertTrue(jpeg.contentEquals(dest.readBytes()))
        assertFalse(bak.exists())

        val older = tinyJpeg()
        dest.writeBytes(older)
        dest.setLastModified(1_000)
        val newer = ByteArray(older.size) { index ->
            if (index == older.size / 2) 0x11 else older[index]
        }
        partial.writeBytes(newer)
        partial.setLastModified(2_000)
        assertTrue(ScenePhoto.recover(dest))
        assertTrue(newer.contentEquals(dest.readBytes()))
        assertFalse(partial.exists())
    }

    @Test fun aFailedRenameStillReplacesATornPicture() {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "atomic-torn")
        dir.mkdirs()
        val dest = File(dir, "torn.jpg")
        val jpeg = tinyJpeg()
        dest.writeBytes(jpeg.copyOf(4))
        ScenePhoto.failNextRenameForTest()
        ScenePhoto.writeAtomically(dest, jpeg)
        assertTrue(jpeg.contentEquals(dest.readBytes()))
        assertFalse(File(dir, "torn.jpg.partial").exists())
    }

    private fun tinyJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.DKGRAY)
        val raw = ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bmp.recycle()
        return raw
    }
}
