package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * A photo staged for a chat or a scene: upright, and small enough to send and to keep in the
 * transcript. The gallery's own file is often sideways (the rotation lives in EXIF, which the
 * model ignores) and far bigger than a reply needs.
 */
object ScenePhoto {
    const val MAX_EDGE = 1536
    const val MIME = "image/jpeg"
    private const val MAX_ENCODED = 1_500_000
    /** Stop shrinking once the long edge is this small. A picture still over the cap is refused. */
    private const val MIN_EDGE = 64
    private const val QUALITY = 82

    /**
     * A photo already in a message, so Edit can put it back in the composer.
     * [fileUri] is a file we own. A data URL in that field is not one.
     */
    data class EditPhoto(val dataUrl: String?, val fileUri: String?)

    /** Bytes to stage, the mime to send, and the file to show when we still have it. */
    class Staged(val bytes: ByteArray, val mime: String, val fileUri: String?)

    fun editPhoto(contentImageUrl: String?, imageUri: String?): EditPhoto? {
        val data = contentImageUrl?.takeIf { it.startsWith("data:image/", ignoreCase = true) }
        val file = imageUri?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        if (data == null && file == null) return null
        return EditPhoto(data, file)
    }

    /** The mime of a data URL, or null when it is not an image. */
    fun mimeFromDataUrl(url: String): String? {
        if (!url.startsWith("data:image/", ignoreCase = true)) return null
        val rest = url.substring(5)
        val end = rest.indexOfAny(charArrayOf(';', ','))
        if (end <= "image/".length) return null
        val mime = rest.substring(0, end).lowercase()
        return mime.takeIf { it.startsWith("image/") && it.length < 40 }
    }

    /** Decoded bytes of a data URL, or null when it is not an image or the payload is bad. */
    fun bytesFromDataUrl(url: String): ByteArray? {
        if (mimeFromDataUrl(url) == null) return null
        val comma = url.indexOf(',')
        if (comma < 0 || comma == url.lastIndex) return null
        val payload = url.substring(comma + 1).trim()
        if (payload.isEmpty()) return null
        return try {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** A bitmap small enough to put in a bubble. The full picture is never decoded. */
    fun bitmap(dataUrl: String, maxEdge: Int): Bitmap? {
        val bytes = bytesFromDataUrl(dataUrl) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxEdge <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxEdge && bounds.outHeight / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    fun stagedFromDataUrl(url: String, maxBytes: Int = 12_000_000): Staged? {
        val bytes = bytesFromDataUrl(url) ?: return null
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        return Staged(bytes, mimeFromDataUrl(url) ?: MIME, null)
    }

    /** JPEG bytes, or null when [raw] is not a picture this device can decode. */
    fun encode(raw: ByteArray): ByteArray? = encode(raw, MAX_EDGE, MAX_ENCODED)

    /**
     * [maxEdge] is the long side before the byte cap. A detailed picture that is still over
     * [maxBytes] after a second, rougher compress is scaled down until it fits. Refusing it
     * used to tell the user the photo was a format we cannot read.
     */
    internal fun encode(raw: ByteArray, maxEdge: Int, maxBytes: Int): ByteArray? {
        if (raw.isEmpty() || maxEdge <= 0 || maxBytes <= 0) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            // Largest sample that keeps the long edge at or above maxEdge; scale() trims the rest.
            // The old rule overshot: a 4032px photo decoded at 1008px, a third under the cap.
            while (sample < 32 && maxSide / (sample * 2) >= maxEdge) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val oriented = applyExif(decoded, raw)
            if (oriented !== decoded) decoded.recycle()
            val scaled = scale(oriented, maxEdge)
            if (scaled !== oriented) oriented.recycle()
            try {
                compress(scaled, maxBytes)
            } finally {
                if (!scaled.isRecycled) scaled.recycle()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * A copy we own, so the bubble can still open the picture after the picker link dies.
     * It lives in app files: the cache is cleared out from under a long chat. The message
     * also carries the JPEG, which is what shows if this file is gone.
     */
    fun store(context: Context, jpeg: ByteArray): Uri? = try {
        if (!completeJpeg(jpeg)) return null
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        // A kill mid-write used to leave a short file that the next open trusted, so the
        // JPEG stored in the message was never put back.
        writeAtomically(file, jpeg)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        null
    }

    /** True when [bytes] is a JPEG that starts with SOI and ends with EOI. */
    fun completeJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return false
        return bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()
    }

    /** True when [file] is a finished JPEG. A short file from a killed write is not. */
    fun completeJpeg(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val start = ByteArray(2)
                raf.readFully(start)
                if (start[0] != 0xFF.toByte() || start[1] != 0xD8.toByte()) return false
                raf.seek(raf.length() - 2)
                val end = ByteArray(2)
                raf.readFully(end)
                end[0] == 0xFF.toByte() && end[1] == 0xD9.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Test hook. [writeAtomically] throws after the new bytes are durable and before
     * they replace a finished side file, as a kill in that window would. Cleared when it fires.
     */
    @androidx.annotation.VisibleForTesting
    internal var stopAfterIncomingForTest: Boolean = false

    /**
     * Writes [bytes] to a side file, syncs it, then renames it over [destination].
     * A crash leaves the side file, not a truncated picture at the real name.
     * The destination is never opened for writing: that truncates a finished picture
     * before the new bytes are durable. The new bytes go to `.partial.incoming` first
     * so a finished `.partial` is not truncated while the rewrite is still in flight.
     */

    internal fun writeAtomically(destination: File, bytes: ByteArray) {
        val dir = destination.parentFile ?: throw IOException("no directory")
        dir.mkdirs()
        // A kill after the bytes were durable used to leave them beside a missing picture.
        recover(destination)
        val partial = File(dir, "${destination.name}.partial")
        // Opening [partial] for write truncates it first. A finished side file the replace
        // could not install yet used to be wiped before the new bytes were durable.
        val incoming = File(dir, "${destination.name}.partial.incoming")
        var replacedPartial = false
        try {
            if (incoming.exists() && !incoming.delete()) {
                throw IOException("Could not replace ${incoming.path}")
            }
            FileOutputStream(incoming).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            syncDirectory(dir)
            if (stopAfterIncomingForTest) {
                stopAfterIncomingForTest = false
                throw IOException("simulated picture write failure")
            }
            val floor = maxOf(
                destination.lastModified(),
                partial.lastModified(),
                File(dir, "${destination.name}.bak").lastModified(),
            )
            if (incoming.lastModified() <= floor) incoming.setLastModified(floor + 1)
            if (!incoming.renameTo(partial)) {
                throw IOException("Could not replace ${partial.path}")
            }
            replacedPartial = true
            if (!install(partial, destination) && !sameBytes(destination, bytes)) {
                throw IOException("Could not replace ${destination.path}")
            }
            replacedPartial = false
            syncDirectory(dir)
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("Could not replace ${destination.path}", e)
        } finally {
            if (replacedPartial && partial.exists()) partial.delete()
        }
    }

    /**
     * Puts a finished side file back at [destination] when the real name is missing, torn,
     * or not newer than that side file. A complete picture is left alone when the side file
     * is older than it.
     */
    internal fun recover(destination: File): Boolean {
        val partial = File(destination.parentFile, "${destination.name}.partial")
        val incoming = File(destination.parentFile, "${destination.name}.partial.incoming")
        val bak = File(destination.parentFile, "${destination.name}.bak")
        // A replace that died after the new bytes were durable, and before they took the name.
        // The picture already there is the previous one. Same timestamp counts: the side file
        // is written second, and a clock that did not tick used to leave the old picture in place.
        // `.partial.incoming` is that side file when the rewrite had not yet replaced `.partial`.
        val staged = stagedSide(incoming, partial)
        if (staged != null && completeJpeg(destination) &&
            staged.lastModified() >= destination.lastModified()
        ) {
            return installFinished(staged, destination, bak)
        }
        if (completeJpeg(destination)) return true
        val source = staged ?: if (completeJpeg(bak)) bak else return false
        return installFinished(source, destination, bak)
    }

    /**
     * Newest finished side file. The same stamp prefers incoming: it is written after partial.
     */
    private fun stagedSide(incoming: File, partial: File): File? {
        val incomingOk = completeJpeg(incoming)
        val partialOk = completeJpeg(partial)
        return when {
            incomingOk && partialOk ->
                if (incoming.lastModified() >= partial.lastModified()) incoming else partial
            incomingOk -> incoming
            partialOk -> partial
            else -> null
        }
    }

    /**
     * [source] is a finished JPEG. A torn [destination] is removed first. A finished one was
     * moved aside by the caller. A kill after that removal still has [source].
     */
    private fun installFinished(source: File, destination: File, bak: File): Boolean {
        if (completeJpeg(destination)) {
            if (bak.exists() && !bak.delete()) return true
            if (!destination.renameTo(bak)) return true
        } else if (destination.exists() && !destination.delete()) {
            return false
        }
        if (!source.renameTo(destination)) {
            if (!destination.exists() && bak.exists()) bak.renameTo(destination)
            return completeJpeg(destination)
        }
        val parent = destination.parentFile
        if (parent != null) {
            for (suffix in listOf(".partial", ".partial.incoming")) {
                val side = File(parent, destination.name + suffix)
                if (side.exists() && side != destination) side.delete()
            }
        }
        if (bak.exists() && bak != destination) bak.delete()
        destination.parentFile?.let { syncDirectory(it) }
        return completeJpeg(destination)
    }

    private fun sameBytes(file: File, bytes: ByteArray): Boolean {
        if (!completeJpeg(file) || file.length() != bytes.size.toLong()) return false
        return try {
            file.readBytes().contentEquals(bytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Tests: the next [rename] returns false once, as a device that refuses to replace does.
     * The finished picture at the destination must still be there afterwards.
     */
    @androidx.annotation.VisibleForTesting
    internal fun failNextRenameForTest() {
        failRenameOnce.set(true)
    }

    private val failRenameOnce = ThreadLocal.withInitial { false }

    private fun rename(from: File, to: File): Boolean {
        if (failRenameOnce.get() == true) {
            failRenameOnce.set(false)
            return false
        }
        return from.renameTo(to)
    }

    /**
     * [tmp] is a finished file in the same directory as [destination]. A rename that fails
     * onto a finished picture leaves that picture. A torn file is not one, so it can be removed
     * and the side file renamed into its place.
     */
    private fun install(tmp: File, destination: File): Boolean {
        if (rename(tmp, destination)) return true
        if (completeJpeg(destination)) return false
        // A torn file is not the picture. Move it aside instead of deleting it, so a kill
        // before the side file takes the name can still be put back.
        val bak = File(destination.parentFile, "${destination.name}.bak")
        if (bak.exists() && !bak.delete()) return false
        if (destination.exists() && !rename(destination, bak)) return false
        if (!rename(tmp, destination)) {
            if (!destination.exists()) rename(bak, destination)
            return false
        }
        bak.delete()
        return true
    }

    private fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
        }
    }

    /** A picture the model sent, kept as a file we own and as the JPEG already in the message. */
    data class GeneratedPicture(val uri: String, val dataUrl: String?)

    fun dataUrl(jpeg: ByteArray): String =
        "data:$MIME;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)

    /** The file behind one of our own links, or null when the link belongs to someone else. */
    fun ownedFile(context: Context, uriString: String): File? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content") return null
        if (uri.authority != "${context.packageName}.fileprovider") return null
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val root = when (segments[0]) {
            CACHE_ROOT -> context.cacheDir
            FILES_ROOT -> context.filesDir
            else -> return null
        }
        if (segments.drop(1).any { it.isEmpty() || it == "." || it == ".." }) return null
        val file = File(root, segments.drop(1).joinToString(File.separator))
        val rootPath = root.canonicalFile.path + File.separator
        val path = file.canonicalFile.path
        if (path != root.canonicalFile.path && !path.startsWith(rootPath)) return null
        return file.canonicalFile
    }

    /** True when [uriString] still opens. A missing cache file is not a picture we can put back. */
    fun canRead(context: Context, uriString: String): Boolean {
        val owned = ownedFile(context, uriString)
        if (owned != null) {
            recover(owned)
            return completeJpeg(owned)
        }
        return readLimited(context, uriString) != null
    }

    /**
     * A finished JPEG in app files. A cache copy, a gallery link, and a file that was only
     * half written are not: those still have to be copied before a swipe drops the bytes
     * stored in the message.
     */
    fun storedFile(context: Context, uriString: String): Boolean {
        val owned = ownedFile(context, uriString) ?: return false
        recover(owned)
        return completeJpeg(owned) && under(context.filesDir, owned)
    }

    /**
     * A link that still opens. A copy left in the cache, a JPEG already stored in the message,
     * or a gallery link we can still read is written under app files. A link we cannot read
     * stays as it is.
     */
    fun settle(context: Context, uriString: String?, embedded: ByteArray?): String? {
        val current = uriString?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
        val owned = current?.let { ownedFile(context, it) }?.also { recover(it) }
        if (owned != null && completeJpeg(owned) && under(context.filesDir, owned)) {
            return current
        }
        val fromFile = owned?.takeIf { completeJpeg(it) }?.readBytes()
        val payload = fromFile ?: embedded?.let { encode(it) }
        if (payload != null) {
            store(context, payload)?.toString()?.let { return it }
        }
        if (current != null && owned == null) {
            val raw = readLimited(context, current) ?: return current
            val jpeg = encode(raw) ?: return current
            return store(context, jpeg)?.toString() ?: current
        }
        return current
    }

    /** [content] plus the generated JPEG, so a later open can draw it after the file is gone. */
    fun embed(content: JsonElement, dataUrl: String): JsonElement {
        val image = buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", dataUrl) })
        }
        val body = MessageContent.unwrap(content).body
        if (MessageContent.imageUrl(body) == dataUrl) return content
        return when (body) {
            is JsonArray -> if (MessageContent.hasImage(body)) body else JsonArray(body + image)
            is JsonPrimitive -> {
                val text = body.contentOrNull.orEmpty()
                buildJsonArray {
                    if (text.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                    }
                    add(image)
                }
            }
            else -> content
        }
    }

    /** The reply's words, with a generated picture left where it was. */
    fun replaceTextKeepingPicture(content: JsonElement, text: String): JsonElement {
        val url = MessageContent.imageUrl(content)?.takeIf { it.startsWith("data:image") }
            ?: return JsonPrimitive(text)
        return embed(JsonPrimitive(text), url)
    }

    fun withGeneratedPicture(message: FlexibleMessage, picture: GeneratedPicture?): FlexibleMessage {
        if (picture == null) return message
        val content = picture.dataUrl?.let { embed(message.content, it) } ?: message.content
        return message.copy(content = content, imageUri = picture.uri)
    }

    /**
     * The file a turn should keep. Roleplay returns as soon as send is asked for, and the
     * composer then clears the staged URI, before the message is built. [useCaptured] means
     * that earlier URI is the one that belongs on the message, even when nothing is staged now.
     * A data URL is not a file.
     */
    fun uriForTurn(useCaptured: Boolean, captured: String?, live: String?): String? {
        val raw = if (useCaptured) captured else live
        return raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
    }

    /** The file name of one of our scene photos, or null when [uriString] is some other link. */
    fun sceneFileName(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        return fileNameIn(uriString)
    }

    /**
     * The `UUID.jpg` in a stored message or a content URI. Anything else is left alone so a
     * caption that mentions the folder cannot delete a file.
     */
    fun fileNameIn(slice: String): String? = fileNamesIn(slice).firstOrNull()

    /** Every scene photo named in [slice], in order, once each. */
    fun fileNamesIn(slice: String): List<String> {
        if (slice.isEmpty()) return emptyList()
        val found = ArrayList<String>(2)
        var from = 0
        while (from < slice.length) {
            val i = slice.indexOf(FILE_MARKER, from)
            if (i < 0) break
            val start = i + FILE_MARKER.length
            val end = start + FILE_NAME_LENGTH
            if (end <= slice.length) {
                val name = slice.substring(start, end)
                if (isSceneFileName(name) && name !in found) found.add(name)
            }
            from = start
        }
        return found
    }

    fun isSceneFileName(name: String): Boolean = SCENE_FILE.matches(name)

    /**
     * A save may drop a picture the new transcript does not name. An edit that already cut
     * that message, and a photo staged in the composer, still need the file.
     */
    internal fun scenePhotosSafeToDelete(
        dropped: Collection<String>,
        heldForEdit: Set<String>,
        pendingName: String?,
        shownNames: Set<String> = emptySet(),
    ): List<String> {
        if (dropped.isEmpty()) return emptyList()
        val pending = pendingName?.takeIf { isSceneFileName(it) }
        // A send puts the file on the transcript before the row exists. A new chat is
        // not saved until the reply lands, so a database check alone deleted the JPEG
        // the bubble was still showing.
        return dropped.filter { it !in heldForEdit && it != pending && it !in shownNames }
    }

    /**
     * Test hook. [deleteWithSides] returns after the side files are gone and before the
     * live file is removed, as a kill in that window would. Cleared when it fires.
     */
    @androidx.annotation.VisibleForTesting
    internal var stopAfterSidesForTest: Boolean = false

    /**
     * Drops `.partial.incoming`, `.partial` and `.bak` before the live file.
     * Removing the live name first used to leave a side file, and the next open put that
     * picture back on the portrait or wallpaper.
     * Returns false when a test stops after the side files.
     */
    internal fun deleteWithSides(destination: File): Boolean {
        val parent = destination.parentFile
        if (parent != null) {
            File(parent, "${destination.name}.partial.incoming").delete()
            File(parent, "${destination.name}.partial").delete()
            File(parent, "${destination.name}.bak").delete()
        }
        if (stopAfterSidesForTest) {
            stopAfterSidesForTest = false
            return false
        }
        destination.delete()
        return true
    }

    /** Deletes scene photos we own. A name that is not one of ours is ignored. */
    fun deleteSceneFiles(context: Context, names: Collection<String>) {
        if (names.isEmpty()) return
        val dir = File(context.filesDir, DIR)
        for (name in names) {
            if (!isSceneFileName(name)) continue
            // Side files first, same as a portrait. Removing the live name first left a side
            // file, and the next open put that picture back.
            deleteWithSides(File(dir, name))
        }
    }

    /** [src] turned upright for the EXIF flag in [raw]. The same bitmap when it already is. */
    internal fun upright(src: Bitmap, raw: ByteArray): Bitmap = applyExif(src, raw)

    private const val DIR = "scene_photos"
    private const val FILE_MARKER = "/owned/scene_photos/"
    private const val FILE_NAME_LENGTH = 40
    private val SCENE_FILE = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg"
    )
    private const val CACHE_ROOT = "temp_images"
    private const val FILES_ROOT = "owned"
    private const val MAX_READ = 8_000_000

    private fun under(root: File, file: File): Boolean {
        val base = root.canonicalFile.path + File.separator
        return file.canonicalFile.path.startsWith(base)
    }

    private fun readLimited(context: Context, uriString: String): ByteArray? = try {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content" && uri.scheme != "file") null
        else context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_READ) return null
                out.write(buf, 0, n)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * JPEG at the usual quality, then a rougher one, then a smaller bitmap, until it fits
     * [maxBytes]. [bmp] itself is left for the caller to recycle.
     */
    private fun compress(bmp: Bitmap, maxBytes: Int): ByteArray? {
        var current = bmp
        var quality = QUALITY
        var scaled = false
        try {
            repeat(12) {
                val out = ByteArrayOutputStream()
                if (!current.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                val bytes = out.toByteArray()
                if (bytes.size <= maxBytes) return bytes
                if (quality != 60) {
                    quality = 60
                    return@repeat
                }
                val edge = maxOf(current.width, current.height)
                if (edge <= MIN_EDGE) return null
                val nw = (current.width * 3 / 4).coerceAtLeast(1)
                val nh = (current.height * 3 / 4).coerceAtLeast(1)
                if (nw == current.width && nh == current.height) return null
                val smaller = Bitmap.createScaledBitmap(current, nw, nh, true)
                if (scaled && smaller !== current) current.recycle()
                if (smaller === current) return null
                current = smaller
                scaled = true
                quality = QUALITY
            }
            return null
        } finally {
            if (scaled && current !== bmp && !current.isRecycled) current.recycle()
        }
    }

    private fun scale(src: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(src.width, src.height)
        if (edge <= maxEdge) return src
        val scale = maxEdge.toFloat() / edge
        val nw = (src.width * scale).toInt().coerceAtLeast(1)
        val nh = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun applyExif(src: Bitmap, raw: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(raw))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_UNDEFINED
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return src
        }
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        } catch (_: Throwable) {
            src
        }
    }
}
