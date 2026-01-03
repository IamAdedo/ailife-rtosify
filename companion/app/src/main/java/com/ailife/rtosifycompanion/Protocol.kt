package com.ailife.rtosifycompanion

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * RTOSify JSON Protocol V1
 *
 * All messages are JSON objects with the following structure:
 * {
 *   "version": 1,
 *   "type": "message_type",
 *   "timestamp": 1234567890,
 *   "data": { ... }
 * }
 */

data class ProtocolMessage(
    val version: Int = 1,
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val data: JsonObject = JsonObject()
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): ProtocolMessage {
            return gson.fromJson(json, ProtocolMessage::class.java)
        }
    }

    fun toJson(): String {
        return gson.toJson(this)
    }

    fun toBytes(): ByteArray {
        return toJson().toByteArray(Charsets.UTF_8)
    }
}

// Message Types
object MessageType {
    const val HEARTBEAT = "heartbeat"
    const val REQUEST_APPS = "request_apps"
    const val RESPONSE_APPS = "response_apps"
    const val NOTIFICATION_POSTED = "notification_posted"
    const val NOTIFICATION_REMOVED = "notification_removed"
    const val DISMISS_NOTIFICATION = "dismiss_notification"
    const val FILE_TRANSFER_START = "file_transfer_start"
    const val FILE_CHUNK = "file_chunk"
    const val FILE_TRANSFER_END = "file_transfer_end"
    const val SHUTDOWN = "shutdown"
    const val REBOOT = "reboot"
    const val LOCK_DEVICE = "lock_device"
    const val FIND_DEVICE = "find_device"
    const val STATUS_UPDATE = "status_update"
    const val SET_DND = "set_dnd"
    const val UNINSTALL_APP = "uninstall_app"
}

// Data classes for specific message types
data class NotificationData(
    val packageName: String,
    val title: String,
    val text: String,
    val key: String
)

data class StatusUpdateData(
    val battery: Int,
    val charging: Boolean,
    val dnd: Boolean,
    val wifi: String
)

data class FileTransferData(
    val name: String,
    val size: Long,
    val checksum: String = ""
)

data class FileChunkData(
    val offset: Long,
    val data: String,  // Base64 encoded chunk
    val chunkNumber: Int,
    val totalChunks: Int
)

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: String  // Base64 encoded PNG
)

// Helper functions to create messages
object ProtocolHelper {
    val gson = Gson()

    fun createHeartbeat(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.HEARTBEAT)
    }

    fun createRequestApps(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_APPS)
    }

    fun createResponseApps(apps: List<AppInfo>): ProtocolMessage {
        val data = JsonObject()
        data.add("apps", gson.toJsonTree(apps))
        return ProtocolMessage(type = MessageType.RESPONSE_APPS, data = data)
    }

    fun createNotificationPosted(notif: NotificationData): ProtocolMessage {
        val data = gson.toJsonTree(notif).asJsonObject
        return ProtocolMessage(type = MessageType.NOTIFICATION_POSTED, data = data)
    }

    fun createNotificationRemoved(key: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("key", key)
        return ProtocolMessage(type = MessageType.NOTIFICATION_REMOVED, data = data)
    }

    fun createDismissNotification(key: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("key", key)
        return ProtocolMessage(type = MessageType.DISMISS_NOTIFICATION, data = data)
    }

    fun createFileTransferStart(fileData: FileTransferData): ProtocolMessage {
        val data = gson.toJsonTree(fileData).asJsonObject
        return ProtocolMessage(type = MessageType.FILE_TRANSFER_START, data = data)
    }

    fun createFileChunk(chunkData: FileChunkData): ProtocolMessage {
        val data = gson.toJsonTree(chunkData).asJsonObject
        return ProtocolMessage(type = MessageType.FILE_CHUNK, data = data)
    }

    fun createFileTransferEnd(success: Boolean = true, error: String = ""): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("success", success)
        data.addProperty("error", error)
        return ProtocolMessage(type = MessageType.FILE_TRANSFER_END, data = data)
    }

    fun createShutdown(force: Boolean = false): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("force", force)
        return ProtocolMessage(type = MessageType.SHUTDOWN, data = data)
    }

    fun createStatusUpdate(status: StatusUpdateData): ProtocolMessage {
        val data = gson.toJsonTree(status).asJsonObject
        return ProtocolMessage(type = MessageType.STATUS_UPDATE, data = data)
    }

    fun createSetDnd(enabled: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.SET_DND, data = data)
    }

    fun createUninstallApp(packageName: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("package", packageName)
        return ProtocolMessage(type = MessageType.UNINSTALL_APP, data = data)
    }

    fun createReboot(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REBOOT)
    }

    fun createLockDevice(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.LOCK_DEVICE)
    }

    fun createFindDevice(enabled: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.FIND_DEVICE, data = data)
    }

    // Helper to extract data from message
    inline fun <reified T> extractData(message: ProtocolMessage): T {
        return gson.fromJson(message.data, T::class.java)
    }

    fun extractStringField(message: ProtocolMessage, field: String): String? {
        return message.data.get(field)?.asString
    }

    fun extractBooleanField(message: ProtocolMessage, field: String): Boolean {
        return message.data.get(field)?.asBoolean ?: false
    }

    fun extractIntField(message: ProtocolMessage, field: String): Int {
        return message.data.get(field)?.asInt ?: 0
    }

    fun extractLongField(message: ProtocolMessage, field: String): Long {
        return message.data.get(field)?.asLong ?: 0L
    }
}
