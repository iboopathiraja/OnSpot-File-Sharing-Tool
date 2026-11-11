package com.beyondzero.onspot.domain.model

/**
 * A device seen on the network. [id] is its IP address (that is what the wire protocol keys on);
 * [name] is the display name with the "OnSpot_" service prefix already removed.
 */
data class Device(
    val id: String,
    val name: String,
    val isConnected: Boolean,
    val avatarIndex: Int = 1,
    val clipboard: ClipboardState = ClipboardState.OFF,
    val lastActivity: Long = 0L
) {
    /** True once both sides agreed to sync clipboards. */
    val shareClipboard: Boolean get() = clipboard == ClipboardState.ON
}

enum class ClipboardState { OFF, PENDING, ON }

data class TransferRecord(
    val id: String,
    val deviceId: String,
    val fileName: String,
    val sizeInBytes: Long,
    val state: TransferState,
    val isIncoming: Boolean,
    val progress: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    val type: TransferType = TransferType.FILE,
    val textContent: String? = null,
    /** Shared by every file sent together in one multi-select; null for a lone file or a message. */
    val batchId: String? = null,
    val sourceUri: String? = null,
    /** Set while an automatic retry is in flight, 1-based attempt number. */
    val retryAttempt: Int? = null,
    /** Name of the peer at the time of the transfer, so history still reads well after its IP changes. */
    val deviceName: String = "",
    /** Bytes moved so far, for speed / ETA. */
    val bytes: Long = 0L,
    /** content:// Uri of a received file, used to open or share it. */
    val localUri: String? = null,
    val error: String? = null,
    val startedAt: Long = 0L
) {
    val isActive: Boolean get() = state == TransferState.PENDING || state == TransferState.TRANSFERRING
}

/** Wire values: the ordinal is sent as an int32, so the order must never change. */
enum class TransferType {
    FILE,
    TEXT,
    CLIPBOARD,
    CLIPBOARD_REQUEST,
    CLIPBOARD_RESPONSE
}

enum class TransferState {
    /** Waiting in this device's send queue. */
    PENDING,
    TRANSFERRING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
