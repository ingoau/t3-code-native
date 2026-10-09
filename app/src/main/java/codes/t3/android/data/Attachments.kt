package codes.t3.android.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/** An image prepared for upload: re-encoded to a server-accepted type and a sane size. */
class PreparedImage(val name: String, val mimeType: String, val bytes: ByteArray, val preview: Bitmap)

object Attachments {
    private const val MAX_EDGE = 2048
    private const val MAX_BYTES = 9 * 1024 * 1024
    private val allowed = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    /** Read a picked image, keeping small PNG/JPEG/WebP/GIF as-is and re-encoding anything else (HEIC, huge photos) to JPEG. */
    suspend fun prepare(resolver: ContentResolver, uri: Uri): PreparedImage = withContext(Dispatchers.IO) {
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "image"
        val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the image")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= MAX_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Unsupported image format")
        val keep = mime in allowed && raw.size <= MAX_BYTES && longest <= MAX_EDGE * 2
        if (keep) return@withContext PreparedImage(name, mime, raw, thumbnail(bitmap))
        val scaled = scaleToFit(bitmap, MAX_EDGE)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        PreparedImage(name.substringBeforeLast('.') + ".jpg", "image/jpeg", out.toByteArray(), thumbnail(scaled))
    }

    private fun scaleToFit(b: Bitmap, edge: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= edge) return b
        val f = edge.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private fun thumbnail(b: Bitmap) = scaleToFit(b, 320)

    /** `attachments.createUploadUrl` → POST bytes → the `ChatAttachment` reference to put in a message. */
    suspend fun upload(conn: EnvironmentConnection, http: okhttp3.OkHttpClient, image: PreparedImage): JsonObject {
        val result = conn.call("attachments.createUploadUrl", buildJsonObject {
            put("type", "image")
            put("name", image.name)
            put("mimeType", image.mimeType)
            put("sizeBytes", image.bytes.size)
        }) as JsonObject
        val id = (result["attachmentId"] as JsonPrimitive).content
        val relative = (result["relativeUrl"] as JsonPrimitive).content
        withContext(Dispatchers.IO) {
            val url = conn.environment.httpBaseUrl.trimEnd('/') + "/" + relative.trimStart('/')
            val request = Request.Builder().url(url).post(image.bytes.toRequestBody(image.mimeType.toMediaType())).build()
            http.newCall(request).execute().use { if (!it.isSuccessful) throw ServerApiException("Upload failed (HTTP ${it.code})") }
        }
        return buildJsonObject {
            put("type", "image")
            put("id", id)
            put("name", image.name)
            put("mimeType", image.mimeType)
            put("sizeBytes", image.bytes.size)
        }
    }

    /** Fetch an attachment's bytes through a short-lived signed URL from `assets.createUrl`. */
    suspend fun download(conn: EnvironmentConnection, http: okhttp3.OkHttpClient, attachment: JsonObject): Bitmap? {
        val id = (attachment["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val result = conn.call("assets.createUrl", buildJsonObject {
            put("resource", buildJsonObject {
                put("_tag", "attachment")
                put("attachmentId", id)
                (attachment["name"] as? JsonPrimitive)?.contentOrNull?.let { put("fileName", it) }
                (attachment["mimeType"] as? JsonPrimitive)?.contentOrNull?.let { put("mimeType", it) }
                put("disposition", "inline")
            })
        }) as JsonObject
        val relative = (result["relativeUrl"] as JsonPrimitive).content
        return withContext(Dispatchers.IO) {
            val url = conn.environment.httpBaseUrl.trimEnd('/') + "/" + relative.trimStart('/')
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val bytes = r.body.bytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = if (bytes.size > 2_000_000) 2 else 1 })
            }
        }
    }
}
