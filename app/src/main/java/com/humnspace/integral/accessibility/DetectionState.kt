package com.humnspace.integral.accessibility

import android.view.KeyEvent
import android.os.SystemClock
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DetectedKeyEvent(
    val keyCode: Int,
    val scanCode: Int,
    val action: String,
    val eventTime: Long,
    val deviceId: Int,
    val deviceName: String,
    val deviceDescriptor: String,
    val keyName: String,
    val isNothingEssentialKey: Boolean
)

object DetectionState {
    private val _events = MutableStateFlow<List<DetectedKeyEvent>>(emptyList())
    val events = _events.asStateFlow()

    fun add(event: KeyEvent) {
        val essentialKey = Build.MANUFACTURER.equals("Nothing", ignoreCase = true) &&
            event.keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode == EssentialKeyService.ESSENTIAL_KEY_SCAN_CODE
        val inputDevice = event.device
        val item = DetectedKeyEvent(event.keyCode, event.scanCode,
            if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP",
            System.currentTimeMillis() - (SystemClock.uptimeMillis() - event.eventTime),
            event.deviceId, inputDevice?.name ?: "Device ${event.deviceId}",
            inputDevice?.descriptor.orEmpty(), displayName(event, essentialKey), essentialKey)
        _events.value = (listOf(item) + _events.value).take(60)
    }

    private fun displayName(event: KeyEvent, essentialKey: Boolean): String = when {
        event.keyCode != KeyEvent.KEYCODE_UNKNOWN -> KeyEvent.keyCodeToString(event.keyCode)
        essentialKey -> "Nothing button (vendor key)"
        else -> "Unknown hardware key (scan ${event.scanCode})"
    }

    fun clear() { _events.value = emptyList() }
}
