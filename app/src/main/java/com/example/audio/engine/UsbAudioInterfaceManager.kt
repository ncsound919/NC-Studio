package com.example.audio.engine

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.example.audio.model.UsbAudioDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages USB Type-C Audio Interface and DAC detection, telemetry, and I/O routing.
 */
class UsbAudioInterfaceManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _usbDevices = MutableStateFlow<List<UsbAudioDeviceInfo>>(emptyList())
    val usbDevices: StateFlow<List<UsbAudioDeviceInfo>> = _usbDevices.asStateFlow()

    private val _activeUsbDevice = MutableStateFlow<UsbAudioDeviceInfo?>(null)
    val activeUsbDevice: StateFlow<UsbAudioDeviceInfo?> = _activeUsbDevice.asStateFlow()

    var onDeviceSelected: ((AudioDeviceInfo?) -> Unit)? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshDevices()
    }

    fun refreshDevices() {
        val devices = audioManager.getDevices(
            AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS
        )
        val usbList = mutableListOf<UsbAudioDeviceInfo>()

        for (dev in devices) {
            val type = dev.type
            val isUsb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            } else false

            if (isUsb) {
                val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dev.productName.toString()
                } else "USB Audio Device"

                val typeLabel = when (type) {
                    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Interface"
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C Headset / DAC"
                    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Host Accessory"
                    else -> "USB Audio"
                }

                val sampleRates = dev.sampleRates.toList().ifEmpty { listOf(44100, 48000, 96000) }
                val channelCounts = dev.channelCounts.toList().ifEmpty { listOf(2) }

                usbList.add(
                    UsbAudioDeviceInfo(
                        id = dev.id,
                        productName = if (name.isBlank()) "Type-C Audio Interface" else name,
                        typeName = typeLabel,
                        isInput = dev.isSource,
                        isOutput = dev.isSink,
                        sampleRates = sampleRates,
                        channelCounts = channelCounts
                    )
                )
            }
        }

        _usbDevices.value = usbList
        if (_activeUsbDevice.value == null && usbList.isNotEmpty()) {
            selectDevice(usbList.first().id)
        } else if (usbList.none { it.id == _activeUsbDevice.value?.id }) {
            _activeUsbDevice.value = null
            onDeviceSelected?.invoke(null)
        }
    }

    fun selectDevice(deviceId: Int) {
        val dev = _usbDevices.value.find { it.id == deviceId }
        _activeUsbDevice.value = dev

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val rawDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val matchingRaw = rawDevices.find { it.id == deviceId }
            onDeviceSelected?.invoke(matchingRaw)
        }
    }

    fun release() {
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {}
    }
}
