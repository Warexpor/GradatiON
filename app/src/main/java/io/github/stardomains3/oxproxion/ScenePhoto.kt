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
    fun encode(raw: ByteArray): ByteArray? {
        if (raw.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxSide / sample > MAX_EDGE) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val oriented = applyExif(decoded, raw)
            if (oriented !== decoded) decoded.recycle()
            val scaled = scale(oriented, MAX_EDGE)
            if (scaled !== oriented) oriented.recycle()
            val jpeg = compress(scaled)
            scaled.recycle()
            jpeg
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
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.writeBytes(jpeg)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        null
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
        if (owned != null) return owned.isFile && owned.length() > 0L
        return readLimited(context, uriString) != null
    }

    /**
     * A link that still opens. A copy left in the cache, a JPEG already stored in the message,
     * or a gallery link we can still read is written under app files. A link we cannot read
     * stays as it is.
     */
    fun settle(context: Context, uriString: String?, embedded: ByteArray?): String? {
        val current = uriString?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
        val owned = current?.let { ownedFile(context, it) }
        if (owned != null && owned.isFile && owned.length() > 0L && under(context.filesDir, owned)) {
            return current
        }
        val fromFile = owned?.takeIf { it.isFile && it.length() > 0L }?.readBytes()
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

    private const val DIR = "scene_photos"
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

    private fun compress(bmp: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
        var bytes = out.toByteArray()
        if (bytes.size > MAX_ENCODED) {
            val again = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 60, again)) return null
            bytes = again.toByteArray()
            if (bytes.size > MAX_ENCODED) return null
        }
        return bytes
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
