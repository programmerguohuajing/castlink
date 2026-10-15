package com.castlink.receiver.dlna.service

/**
 * UPnP RenderingControl:1 服务实现（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 * 音量映射到系统媒体音量（实现见 DlnaMediaBridge）。
 */
class RenderingControlService(private val control: DlnaControl) {

    companion object {
        const val SERVICE_TYPE = "urn:schemas-upnp-org:service:RenderingControl:1"
        const val CONTROL_PATH = "/control/rc"
    }

    fun handle(action: String, args: Map<String, String>): Map<String, String> {
        return when (action) {
            "GetMute" -> mapOf("CurrentMute" to if (control.isMuted()) "1" else "0")
            "SetMute" -> {
                val want = args["DesiredMute"].orEmpty().trim()
                control.setMuted(want == "1" || want.equals("true", ignoreCase = true))
                emptyMap()
            }
            "GetVolume" -> mapOf("CurrentVolume" to control.getVolumePercent().coerceIn(0, 100).toString())
            "SetVolume" -> {
                val v = args["DesiredVolume"]?.trim()?.toIntOrNull()
                    ?: throw UpnpError(402, "Invalid Args: bad DesiredVolume")
                control.setVolumePercent(v.coerceIn(0, 100))
                emptyMap()
            }
            "ListPresets" -> mapOf("CurrentPresetNameList" to "FactoryDefaults")
            "SelectPreset" -> emptyMap<String, String>() // 忽略：无预设概念
            else -> throw UpnpError(401, "Invalid Action")
        }
    }
}
