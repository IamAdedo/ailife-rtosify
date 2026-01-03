# JSON Protocol Migration Guide

This guide will help you complete the migration from binary protocol to JSON protocol.

## Pattern Overview

### Old Pattern (Binary):
```kotlin
// Sending
sendPacket(TYPE_SOMETHING, data.toByteArray())

// Receiving
when (type) {
    TYPE_SOMETHING -> handleSomething(readString(inputStream))
}

// Handler
private fun handleSomething(data: String) {
    // parse data manually
}
```

### New Pattern (JSON):
```kotlin
// Sending
sendMessage(ProtocolHelper.createSomething(data))

// Receiving
when (message.type) {
    MessageType.SOMETHING -> handleSomething(message)
}

// Handler
private fun handleSomething(message: ProtocolMessage) {
    val data = ProtocolHelper.extractData<DataType>(message)
    // or
    val field = ProtocolHelper.extractStringField(message, "fieldName")
}
```

---

## Phone App - Remaining Updates

### 1. handleRequestApps() - NEW METHOD

**Create this method:**
```kotlin
private fun handleRequestApps() {
    val apps = getInstalledAppsWithIcons()
    sendMessage(ProtocolHelper.createResponseApps(apps))
}
```

**Update getInstalledAppsJsonWithIcons():**

Find the current method (around line 800):
```kotlin
private fun getInstalledAppsJsonWithIcons(): String {
    val apps = mutableListOf<JSONObject>()
    // ... code that builds JSONObject list
    return JSONArray(apps).toString()
}
```

Replace with:
```kotlin
private fun getInstalledAppsWithIcons(): List<AppInfo> {
    val apps = mutableListOf<AppInfo>()
    val pm = packageManager
    val packages = pm.getInstalledPackages(0)

    for (packageInfo in packages) {
        try {
            val appName = packageInfo.applicationInfo.loadLabel(pm).toString()
            val packageName = packageInfo.packageName

            // Get icon and convert to base64
            val icon = packageInfo.applicationInfo.loadIcon(pm)
            val bitmap = icon.toBitmap(48, 48)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val iconBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            apps.add(AppInfo(
                name = appName,
                packageName = packageName,
                icon = iconBase64
            ))
        } catch (_: Exception) {}
    }

    return apps
}
```

**Add this import:**
```kotlin
import android.util.Base64
import java.io.ByteArrayOutputStream
```

---

### 2. handleResponseApps(message: ProtocolMessage)

**Find the old method:**
```kotlin
private fun handleTextMessage(message: String) {
    if (message == CMD_REQUEST_APPS) {
        val appListJson = getInstalledAppsJsonWithIcons()
        sendPacket(TYPE_TEXT_CMD, (CMD_RESPONSE_APPS + appListJson).toByteArray())
    }
    else if (message.startsWith(CMD_RESPONSE_APPS)) {
        val jsonPart = message.substring(CMD_RESPONSE_APPS.length)
        CoroutineScope(Dispatchers.Main).launch { callback?.onAppListReceived(jsonPart) }
    }
}
```

**Replace with:**
```kotlin
private fun handleResponseApps(message: ProtocolMessage) {
    try {
        val appsJsonArray = message.data.getAsJsonArray("apps")
        val gson = com.google.gson.Gson()
        val apps = gson.fromJson(appsJsonArray, Array<AppInfo>::class.java).toList()

        // Convert back to JSON string for callback (temporary until callback is updated)
        val jsonArray = org.json.JSONArray()
        for (app in apps) {
            val obj = org.json.JSONObject()
            obj.put("name", app.name)
            obj.put("package", app.packageName)
            obj.put("icon", app.icon)
            jsonArray.put(obj)
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            callback?.onAppListReceived(jsonArray.toString())
        }
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Error parsing app list: ${e.message}")
    }
}
```

**Delete the old handleTextMessage() method entirely.**

---

### 3. showMirroredNotification(message: ProtocolMessage)

**Find the old method:**
```kotlin
private fun showMirroredNotification(jsonString: String) {
    try {
        val json = JSONObject(jsonString)
        val pkg = json.optString("package", "")
        val title = json.optString("title", "")
        val text = json.optString("text", "")
        val key = json.optString("key", "")
        // ... rest of code
    } catch (e: Exception) {
        Log.e(TAG, "Erro ao exibir notificação: ${e.message}")
    }
}
```

**Replace with:**
```kotlin
private fun showMirroredNotification(message: ProtocolMessage) {
    try {
        val notif = ProtocolHelper.extractData<NotificationData>(message)

        val pkg = notif.packageName
        val title = notif.title
        val text = notif.text
        val key = notif.key

        // ... rest of the existing code remains the same

    } catch (e: Exception) {
        android.util.Log.e(TAG, "Erro ao exibir notificação: ${e.message}")
    }
}
```

---

### 4. dismissLocalNotification(message: ProtocolMessage)

**Find the old method:**
```kotlin
private fun dismissLocalNotification(key: String) {
    val notifId = notificationMap[key]
    if (notifId != null) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifId)
        notificationMap.remove(key)
    }
}
```

**Replace with:**
```kotlin
private fun dismissLocalNotification(message: ProtocolMessage) {
    val key = ProtocolHelper.extractStringField(message, "key") ?: return
    val notifId = notificationMap[key]
    if (notifId != null) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifId)
        notificationMap.remove(key)
    }
}
```

---

### 5. requestDismissOnPhone(message: ProtocolMessage)

**Find the old method:**
```kotlin
private fun requestDismissOnPhone(key: String) {
    val intent = Intent(ACTION_CMD_DISMISS_ON_PHONE).apply {
        putExtra(EXTRA_NOTIF_KEY, key)
        setPackage(packageName)
    }
    sendBroadcast(intent)
}
```

**Replace with:**
```kotlin
private fun requestDismissOnPhone(message: ProtocolMessage) {
    val key = ProtocolHelper.extractStringField(message, "key") ?: return
    val intent = Intent(ACTION_CMD_DISMISS_ON_PHONE).apply {
        putExtra(EXTRA_NOTIF_KEY, key)
        setPackage(packageName)
    }
    sendBroadcast(intent)
}
```

---

### 6. handleSetDndCommand(message: ProtocolMessage)

**Find the old method:**
```kotlin
private fun handleSetDndCommand(command: String) {
    val enable = command.toBoolean()
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    // ... rest of code
}
```

**Replace with:**
```kotlin
private fun handleSetDndCommand(message: ProtocolMessage) {
    val enable = ProtocolHelper.extractBooleanField(message, "enabled")
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    // ... rest of existing code remains the same
}
```

---

### 7. handleFileTransfer(message: ProtocolMessage) - NEW METHOD

**Add this new method:**
```kotlin
private suspend fun handleFileTransfer(message: ProtocolMessage) {
    try {
        val fileData = ProtocolHelper.extractData<FileTransferData>(message)

        // Decode base64 content
        val fileBytes = android.util.Base64.decode(fileData.content, android.util.Base64.DEFAULT)

        // Save to temp file
        val tempFile = File(cacheDir, fileData.name)
        tempFile.writeBytes(fileBytes)

        // Verify checksum if provided
        if (fileData.checksum.isNotEmpty()) {
            val md5 = calculateMd5(tempFile)
            if (md5 != fileData.checksum) {
                android.util.Log.e(TAG, "File checksum mismatch!")
                tempFile.delete()
                return
            }
        }

        // Show install prompt
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            showInstallApkDialog(tempFile)
        }

    } catch (e: Exception) {
        android.util.Log.e(TAG, "Error handling file transfer: ${e.message}")
    }
}

private fun calculateMd5(file: File): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    file.inputStream().use { fis ->
        val buffer = ByteArray(8192)
        var read: Int
        while (fis.read(buffer).also { read = it } != -1) {
            md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
```

---

### 8. sendApkFile() - UPDATE FILE SENDING

**Find the old method (around line 660):**
```kotlin
fun sendApkFile(uri: Uri) {
    serviceScope.launch(Dispatchers.IO) {
        isTransferring = true
        val out = globalOutputStream ?: return@launch

        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val fileSize = inputStream.available().toLong()

                // Send TYPE_FILE_START with size
                synchronized(out) {
                    out.writeByte(TYPE_FILE_START)
                    out.writeLong(fileSize)
                    out.flush()
                }

                // Send chunks
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalSent = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    synchronized(out) {
                        out.writeByte(TYPE_FILE_CHUNK)
                        out.writeInt(bytesRead)
                        out.write(buffer, 0, bytesRead)
                    }
                    totalSent += bytesRead
                    val progress = (totalSent * 100 / fileSize).toInt()
                    withContext(Dispatchers.Main) {
                        callback?.onUploadProgress(progress)
                    }
                }

                // Send TYPE_FILE_END
                synchronized(out) {
                    out.writeByte(TYPE_FILE_END)
                    out.flush()
                }
            }
        } catch (e: Exception) {
            // error handling
        } finally {
            isTransferring = false
        }
    }
}
```

**Replace with JSON-based transfer:**
```kotlin
fun sendApkFile(uri: Uri) {
    serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        isTransferring = true

        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                // Read entire file into memory (for files < 10MB this is acceptable)
                val fileBytes = inputStream.readBytes()

                // Calculate MD5 checksum
                val md5 = java.security.MessageDigest.getInstance("MD5")
                md5.update(fileBytes)
                val checksum = md5.digest().joinToString("") { "%02x".format(it) }

                // Encode to base64
                val base64Content = android.util.Base64.encodeToString(
                    fileBytes,
                    android.util.Base64.NO_WRAP
                )

                // Get filename from URI
                val fileName = getFileNameFromUri(uri) ?: "app.apk"

                // Create file transfer message
                val fileData = FileTransferData(
                    name = fileName,
                    size = fileBytes.size.toLong(),
                    checksum = checksum,
                    content = base64Content
                )

                sendMessage(ProtocolHelper.createFileTransfer(fileData))

                // Report 100% progress
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    callback?.onUploadProgress(100)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error sending file: ${e.message}")
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                callback?.onUploadProgress(-1)
            }
        } finally {
            isTransferring = false
        }
    }
}

private fun getFileNameFromUri(uri: Uri): String? {
    var fileName: String? = null
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                fileName = cursor.getString(nameIndex)
            }
        }
    }
    return fileName
}
```

**Delete these old file handling methods:**
- `handleFileStart()`
- `handleFileChunk()`
- `handleFileEnd()`

---

### 9. sendShutdownCommand()

**Find:**
```kotlin
fun sendShutdownCommand() {
    sendPacket(TYPE_SHUTDOWN_COMMAND, ByteArray(0))
}
```

**Replace with:**
```kotlin
fun sendShutdownCommand() {
    sendMessage(ProtocolHelper.createShutdown())
}
```

---

### 10. Find and Fix Remaining collectWatchStatus Call

Search for any remaining calls like:
```kotlin
val statusJson = collectWatchStatus()
sendPacket(TYPE_STATUS_UPDATE, statusJson.toString().toByteArray(Charsets.UTF_8))
```

Replace with:
```kotlin
val status = collectWatchStatus()
sendMessage(ProtocolHelper.createStatusUpdate(status))
```

---

## Watch App (Companion) - Same Pattern

Apply the **exact same patterns** to `/home/ailife/AndroidStudioProjects/rtosify/companion/app/src/main/java/com/ailife/rtosifycompanion/BluetoothService.kt`

The watch app has the same structure, so:
1. Replace all `sendPacket()` calls with `sendMessage()`
2. Update the message receive loop (same as phone)
3. Update all handler methods to accept `ProtocolMessage`
4. Update `collectWatchStatus()` to return `StatusUpdateData`
5. Update file transfer methods

---

## Testing Checklist

After completing all updates:

- [ ] Phone and watch apps compile without errors
- [ ] Heartbeat keeps connection alive
- [ ] Notifications sync from phone to watch
- [ ] Notifications can be dismissed from watch
- [ ] DND toggle works
- [ ] Status updates (battery, WiFi) display correctly
- [ ] App list request/response works
- [ ] APK file transfer works
- [ ] Shutdown command works

---

## Common Issues & Solutions

### Issue: Gson parsing errors
**Solution:** Make sure all data classes match the JSON structure exactly. Use `@SerializedName` if field names differ.

### Issue: ClassCastException
**Solution:** Use `ProtocolHelper.extractData<Type>()` instead of direct casting.

### Issue: Missing imports
**Add these imports as needed:**
```kotlin
import com.google.gson.Gson
import com.google.gson.JsonObject
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
```

### Issue: NULL pointer exceptions
**Solution:** Use safe calls and provide defaults:
```kotlin
val value = ProtocolHelper.extractStringField(message, "field") ?: "default"
```

---

## Quick Reference: Helper Functions

```kotlin
// Extract typed data
val data = ProtocolHelper.extractData<NotificationData>(message)

// Extract individual fields
val str = ProtocolHelper.extractStringField(message, "fieldName")
val bool = ProtocolHelper.extractBooleanField(message, "fieldName")
val int = ProtocolHelper.extractIntField(message, "fieldName")
val long = ProtocolHelper.extractLongField(message, "fieldName")

// Create messages
ProtocolHelper.createHeartbeat()
ProtocolHelper.createRequestApps()
ProtocolHelper.createResponseApps(apps: List<AppInfo>)
ProtocolHelper.createNotificationPosted(notif: NotificationData)
ProtocolHelper.createNotificationRemoved(key: String)
ProtocolHelper.createDismissNotification(key: String)
ProtocolHelper.createFileTransfer(fileData: FileTransferData)
ProtocolHelper.createShutdown(force: Boolean = false)
ProtocolHelper.createStatusUpdate(status: StatusUpdateData)
ProtocolHelper.createSetDnd(enabled: Boolean)
```

---

Good luck with the migration! The pattern is consistent throughout, so once you understand it, the rest should be straightforward.
