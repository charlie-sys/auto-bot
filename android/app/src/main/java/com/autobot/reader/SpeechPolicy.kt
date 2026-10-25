package com.autobot.reader

/** AudioDeviceInfo values, separated from Android calls so policy can be tested. */
internal object SpeechPolicy {
    private const val CLASSIC_BLUETOOTH_MEDIA = 8 // TYPE_BLUETOOTH_A2DP
    private const val LE_BLUETOOTH_HEADSET = 26 // TYPE_BLE_HEADSET (API 31)

    fun isBluetoothMediaOutput(type: Int): Boolean =
        type == CLASSIC_BLUETOOTH_MEDIA || type == LE_BLUETOOTH_HEADSET

    fun canSpeak(muted: Boolean, bluetoothOnly: Boolean, outputTypes: List<Int>): Boolean =
        !muted && (!bluetoothOnly || outputTypes.any(::isBluetoothMediaOutput))
}
