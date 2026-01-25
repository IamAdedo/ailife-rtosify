package com.ailife.rtosifycompanion

import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Apple Notification Center Service (ANCS) Client
 *
 * Connects to iOS device's ANCS service via BLE to receive notifications.
 * Used when iOS app is connected since iOS cannot forward notifications like Android.
 *
 * ANCS Protocol Reference: https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/
 */
class AncsClient(
    private val context: Context,
    private val callback: AncsCallback
) {
    companion object {
        private const val TAG = "AncsClient"

        // ANCS Service UUID
        val ANCS_SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")

        // ANCS Characteristic UUIDs
        val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

        // Client Characteristic Configuration Descriptor
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Event IDs
        const val EVENT_ID_NOTIFICATION_ADDED = 0
        const val EVENT_ID_NOTIFICATION_MODIFIED = 1
        const val EVENT_ID_NOTIFICATION_REMOVED = 2

        // Event Flags
        const val EVENT_FLAG_SILENT = 1
        const val EVENT_FLAG_IMPORTANT = 2
        const val EVENT_FLAG_PRE_EXISTING = 4
        const val EVENT_FLAG_POSITIVE_ACTION = 8
        const val EVENT_FLAG_NEGATIVE_ACTION = 16

        // Category IDs
        const val CATEGORY_OTHER = 0
        const val CATEGORY_INCOMING_CALL = 1
        const val CATEGORY_MISSED_CALL = 2
        const val CATEGORY_VOICEMAIL = 3
        const val CATEGORY_SOCIAL = 4
        const val CATEGORY_SCHEDULE = 5
        const val CATEGORY_EMAIL = 6
        const val CATEGORY_NEWS = 7
        const val CATEGORY_HEALTH_FITNESS = 8
        const val CATEGORY_BUSINESS_FINANCE = 9
        const val CATEGORY_LOCATION = 10
        const val CATEGORY_ENTERTAINMENT = 11

        // Command IDs
        const val COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES = 0
        const val COMMAND_ID_GET_APP_ATTRIBUTES = 1
        const val COMMAND_ID_PERFORM_NOTIFICATION_ACTION = 2

        // Notification Attribute IDs
        const val NOTIFICATION_ATTR_APP_IDENTIFIER = 0
        const val NOTIFICATION_ATTR_TITLE = 1
        const val NOTIFICATION_ATTR_SUBTITLE = 2
        const val NOTIFICATION_ATTR_MESSAGE = 3
        const val NOTIFICATION_ATTR_MESSAGE_SIZE = 4
        const val NOTIFICATION_ATTR_DATE = 5
        const val NOTIFICATION_ATTR_POSITIVE_ACTION_LABEL = 6
        const val NOTIFICATION_ATTR_NEGATIVE_ACTION_LABEL = 7

        // Action IDs
        const val ACTION_ID_POSITIVE = 0
        const val ACTION_ID_NEGATIVE = 1
    }

    interface AncsCallback {
        fun onAncsConnected()
        fun onAncsDisconnected()
        fun onNotificationReceived(notification: AncsNotification)
        fun onNotificationRemoved(notificationUID: Int)
        fun onError(message: String)
    }

    data class AncsNotification(
        val uid: Int,
        val eventId: Int,
        val eventFlags: Int,
        val categoryId: Int,
        val categoryCount: Int,
        var appIdentifier: String? = null,
        var title: String? = null,
        var subtitle: String? = null,
        var message: String? = null,
        var date: String? = null,
        var positiveActionLabel: String? = null,
        var negativeActionLabel: String? = null
    )

    private var bluetoothGatt: BluetoothGatt? = null
    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isConnected = false

    // Pending notifications awaiting attribute data
    private val pendingNotifications = ConcurrentHashMap<Int, AncsNotification>()

    // Buffer for fragmented data source responses
    private var dataBuffer = ByteArray(0)
    private var currentNotificationUID: Int? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to iOS device GATT server")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from iOS device")
                    isConnected = false
                    cleanup()
                    callback.onAncsDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered")
                setupAncsService(gatt)
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                callback.onError("Service discovery failed")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                NOTIFICATION_SOURCE_UUID -> handleNotificationSource(value)
                DATA_SOURCE_UUID -> handleDataSource(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { value ->
                when (characteristic.uuid) {
                    NOTIFICATION_SOURCE_UUID -> handleNotificationSource(value)
                    DATA_SOURCE_UUID -> handleDataSource(value)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor written successfully for ${descriptor.characteristic.uuid}")

                // After enabling notifications on one characteristic, enable the next
                when (descriptor.characteristic.uuid) {
                    NOTIFICATION_SOURCE_UUID -> {
                        // Enable Data Source notifications next
                        dataSourceChar?.let { enableNotifications(gatt, it) }
                    }
                    DATA_SOURCE_UUID -> {
                        // All set up, we're ready
                        isConnected = true
                        Log.i(TAG, "ANCS fully initialized - ready to receive notifications")
                        callback.onAncsConnected()
                    }
                }
            } else {
                Log.e(TAG, "Descriptor write failed: $status")
                callback.onError("Failed to enable notifications")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: $status")
            }
        }
    }

    /**
     * Connect to the iOS device's ANCS service
     */
    fun connect(device: BluetoothDevice) {
        Log.i(TAG, "Connecting to iOS device: ${device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Disconnect from ANCS
     */
    fun disconnect() {
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        isConnected = false
        cleanup()
    }

    private fun cleanup() {
        pendingNotifications.clear()
        dataBuffer = ByteArray(0)
        currentNotificationUID = null
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
    }

    private fun setupAncsService(gatt: BluetoothGatt) {
        val ancsService = gatt.getService(ANCS_SERVICE_UUID)
        if (ancsService == null) {
            Log.e(TAG, "ANCS service not found on device")
            callback.onError("ANCS service not found - make sure the iOS device is paired and ANCS is enabled")
            return
        }

        Log.i(TAG, "Found ANCS service")

        // Get characteristics
        notificationSourceChar = ancsService.getCharacteristic(NOTIFICATION_SOURCE_UUID)
        controlPointChar = ancsService.getCharacteristic(CONTROL_POINT_UUID)
        dataSourceChar = ancsService.getCharacteristic(DATA_SOURCE_UUID)

        if (notificationSourceChar == null || controlPointChar == null || dataSourceChar == null) {
            Log.e(TAG, "Required ANCS characteristics not found")
            callback.onError("ANCS characteristics not found")
            return
        }

        Log.i(TAG, "Found all ANCS characteristics, enabling notifications...")

        // Enable notifications on Notification Source first
        enableNotifications(gatt, notificationSourceChar!!)
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        } else {
            Log.e(TAG, "CCCD descriptor not found for ${characteristic.uuid}")
        }
    }

    /**
     * Handle notification from Notification Source characteristic
     * Format: EventID (1) | EventFlags (1) | CategoryID (1) | CategoryCount (1) | NotificationUID (4)
     */
    private fun handleNotificationSource(data: ByteArray) {
        if (data.size < 8) {
            Log.e(TAG, "Invalid notification source data length: ${data.size}")
            return
        }

        val eventId = data[0].toInt() and 0xFF
        val eventFlags = data[1].toInt() and 0xFF
        val categoryId = data[2].toInt() and 0xFF
        val categoryCount = data[3].toInt() and 0xFF
        val notificationUID = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

        Log.d(TAG, "Notification: eventId=$eventId, categoryId=$categoryId, uid=$notificationUID")

        when (eventId) {
            EVENT_ID_NOTIFICATION_ADDED, EVENT_ID_NOTIFICATION_MODIFIED -> {
                // Skip silent/pre-existing if desired
                if ((eventFlags and EVENT_FLAG_PRE_EXISTING) != 0) {
                    Log.d(TAG, "Skipping pre-existing notification")
                    return
                }

                val notification = AncsNotification(
                    uid = notificationUID,
                    eventId = eventId,
                    eventFlags = eventFlags,
                    categoryId = categoryId,
                    categoryCount = categoryCount
                )
                pendingNotifications[notificationUID] = notification

                // Request notification attributes
                requestNotificationAttributes(notificationUID)
            }
            EVENT_ID_NOTIFICATION_REMOVED -> {
                pendingNotifications.remove(notificationUID)
                callback.onNotificationRemoved(notificationUID)
            }
        }
    }

    /**
     * Request detailed notification attributes from Control Point
     */
    private fun requestNotificationAttributes(notificationUID: Int) {
        val gatt = bluetoothGatt ?: return
        val controlPoint = controlPointChar ?: return

        // Build command: CommandID (1) | NotificationUID (4) | AttributeIDs...
        // Each attribute ID can optionally have a max length (2 bytes) for string attributes
        val command = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES.toByte())
            putInt(notificationUID)
            // Request attributes with max lengths
            put(NOTIFICATION_ATTR_APP_IDENTIFIER.toByte())
            put(NOTIFICATION_ATTR_TITLE.toByte())
            putShort(64) // max title length
            put(NOTIFICATION_ATTR_SUBTITLE.toByte())
            putShort(64) // max subtitle length
            put(NOTIFICATION_ATTR_MESSAGE.toByte())
            putShort(512) // max message length
            put(NOTIFICATION_ATTR_DATE.toByte())
        }.array()

        controlPoint.value = command
        controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(controlPoint)

        Log.d(TAG, "Requested attributes for notification $notificationUID")
    }

    /**
     * Handle data from Data Source characteristic
     * Response may be fragmented across multiple packets
     */
    private fun handleDataSource(data: ByteArray) {
        // Append to buffer
        dataBuffer += data

        // Try to parse the response
        if (dataBuffer.size < 5) return // Need at least command ID + notification UID

        val buffer = ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN)
        val commandId = buffer.get().toInt() and 0xFF

        if (commandId != COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES) {
            Log.d(TAG, "Ignoring non-notification-attribute response: $commandId")
            dataBuffer = ByteArray(0)
            return
        }

        val notificationUID = buffer.int
        val notification = pendingNotifications[notificationUID]

        if (notification == null) {
            Log.w(TAG, "Received attributes for unknown notification: $notificationUID")
            dataBuffer = ByteArray(0)
            return
        }

        // Parse attributes
        try {
            while (buffer.remaining() >= 3) {
                val attrId = buffer.get().toInt() and 0xFF
                val attrLength = buffer.short.toInt() and 0xFFFF

                if (buffer.remaining() < attrLength) {
                    // Need more data, wait for next packet
                    return
                }

                val attrValue = if (attrLength > 0) {
                    val bytes = ByteArray(attrLength)
                    buffer.get(bytes)
                    String(bytes, Charsets.UTF_8)
                } else {
                    null
                }

                when (attrId) {
                    NOTIFICATION_ATTR_APP_IDENTIFIER -> notification.appIdentifier = attrValue
                    NOTIFICATION_ATTR_TITLE -> notification.title = attrValue
                    NOTIFICATION_ATTR_SUBTITLE -> notification.subtitle = attrValue
                    NOTIFICATION_ATTR_MESSAGE -> notification.message = attrValue
                    NOTIFICATION_ATTR_DATE -> notification.date = attrValue
                    NOTIFICATION_ATTR_POSITIVE_ACTION_LABEL -> notification.positiveActionLabel = attrValue
                    NOTIFICATION_ATTR_NEGATIVE_ACTION_LABEL -> notification.negativeActionLabel = attrValue
                }
            }

            // All attributes parsed, notify callback
            pendingNotifications.remove(notificationUID)
            dataBuffer = ByteArray(0)

            Log.i(TAG, "Complete notification: ${notification.title} - ${notification.message}")
            callback.onNotificationReceived(notification)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing notification attributes: ${e.message}")
            dataBuffer = ByteArray(0)
        }
    }

    /**
     * Perform notification action (positive/negative)
     */
    fun performNotificationAction(notificationUID: Int, actionId: Int) {
        val gatt = bluetoothGatt ?: return
        val controlPoint = controlPointChar ?: return

        val command = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(COMMAND_ID_PERFORM_NOTIFICATION_ACTION.toByte())
            putInt(notificationUID)
            put(actionId.toByte())
        }.array()

        controlPoint.value = command
        controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(controlPoint)

        Log.d(TAG, "Performed action $actionId on notification $notificationUID")
    }

    /**
     * Get category name for display
     */
    fun getCategoryName(categoryId: Int): String {
        return when (categoryId) {
            CATEGORY_OTHER -> "Other"
            CATEGORY_INCOMING_CALL -> "Incoming Call"
            CATEGORY_MISSED_CALL -> "Missed Call"
            CATEGORY_VOICEMAIL -> "Voicemail"
            CATEGORY_SOCIAL -> "Social"
            CATEGORY_SCHEDULE -> "Schedule"
            CATEGORY_EMAIL -> "Email"
            CATEGORY_NEWS -> "News"
            CATEGORY_HEALTH_FITNESS -> "Health & Fitness"
            CATEGORY_BUSINESS_FINANCE -> "Business & Finance"
            CATEGORY_LOCATION -> "Location"
            CATEGORY_ENTERTAINMENT -> "Entertainment"
            else -> "Unknown"
        }
    }

    fun isConnected(): Boolean = isConnected
}
