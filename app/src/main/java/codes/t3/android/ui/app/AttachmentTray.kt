package codes.t3.android.ui.app

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import codes.t3.android.data.Attachments
import codes.t3.android.data.T3Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** An image the user picked; [ref] is set once the upload finishes. */
data class PendingAttachment(val key: String, val preview: ImageBitmap?, val ref: JsonObject? = null, val error: String? = null) {
    val uploading: Boolean get() = ref == null && error == null
}

/** Picks, uploads and tracks composer attachments for one environment. */
class AttachmentTray(private val scope: CoroutineScope, private val repository: T3Repository) {
    private val _items = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val items: StateFlow<List<PendingAttachment>> = _items

    fun add(environmentId: String, resolver: ContentResolver, uris: List<Uri>) {
        val conn = repository.connection(environmentId) ?: return UiEvents.show("Environment is not connected")
        uris.take(10 - _items.value.size).forEach { uri ->
            val key = UUID.randomUUID().toString()
            _items.update { it + PendingAttachment(key, null) }
            scope.launch {
                runCatching {
                    val image = Attachments.prepare(resolver, uri)
                    _items.update { list -> list.map { if (it.key == key) it.copy(preview = image.preview.asImageBitmap()) else it } }
                    Attachments.upload(conn, repository.http, image)
                }.onSuccess { ref ->
                    _items.update { list -> list.map { if (it.key == key) it.copy(ref = ref) else it } }
                }.onFailure { e ->
                    _items.update { list -> list.map { if (it.key == key) it.copy(error = errorText(e)) else it } }
                    UiEvents.show("Couldn't attach image: ${errorText(e)}")
                }
            }
        }
    }

    fun remove(key: String) {
        val removed = _items.value.firstOrNull { it.key == key }
        _items.update { list -> list.filterNot { it.key == key } }
        removed?.ref?.get("id")?.let { id ->
            // Best effort cleanup of the orphaned upload.
            scope.launch { runCatching { repository.connections.value.values.forEach { c -> c.call("attachments.delete", JsonObject(mapOf("attachmentId" to id))) } } }
        }
    }

    /** Uploaded refs to send, clearing the tray. */
    fun take(): JsonArray {
        val refs = _items.value.mapNotNull { it.ref }
        _items.value = emptyList()
        return JsonArray(refs)
    }

    val ready: Boolean get() = _items.value.none { it.uploading }
}
