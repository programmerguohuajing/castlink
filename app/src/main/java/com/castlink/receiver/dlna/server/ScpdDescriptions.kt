package com.castlink.receiver.dlna.server

/**
 * 三大服务的 SCPD（服务控制规范）XML 模板（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 动作/参数集合按 DMR 实际实现的最小完备集裁剪：客户端（Cling 等库）会先 GET
 * 这些 XML 做能力探测，缺失常用 action 会导致投屏按钮灰掉。
 */
object ScpdDescriptions {

    /** @param kind "avt" | "rc" | "cm"，未知返回 null */
    fun get(kind: String): String? = when (kind) {
        "avt" -> AVT
        "rc" -> RC
        "cm" -> CM
        else -> null
    }

    private val AVT = """<?xml version="1.0" encoding="utf-8"?>""" +
        """<scpd xmlns="urn:schemas-upnp-org:service-1-0">""" +
        "<specVersion><major>1</major><minor>0</minor></specVersion>" +
        "<actionList>" +
        action("SetAVTransportURI",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("CurrentURI", "in", "AVTransportURI"),
            arg("CurrentURIMetaData", "in", "AVTransportURIMetaData")) +
        action("SetNextAVTransportURI",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("NextURI", "in", "NextAVTransportURI"),
            arg("NextURIMetaData", "in", "NextAVTransportURIMetaData")) +
        action("GetMediaInfo",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("NrTracks", "out", "NumberOfTracks"),
            arg("MediaDuration", "out", "CurrentMediaDuration"),
            arg("CurrentURI", "out", "AVTransportURI"),
            arg("CurrentURIMetaData", "out", "AVTransportURIMetaData"),
            arg("NextURI", "out", "NextAVTransportURI"),
            arg("NextURIMetaData", "out", "NextAVTransportURIMetaData"),
            arg("PlayMedium", "out", "PlaybackStorageMedium"),
            arg("RecordMedium", "out", "RecordStorageMedium"),
            arg("WriteStatus", "out", "WriteStatus")) +
        action("GetTransportInfo",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("CurrentTransportState", "out", "TransportState"),
            arg("CurrentTransportStatus", "out", "TransportStatus"),
            arg("CurrentSpeed", "out", "TransportPlaySpeed")) +
        action("GetPositionInfo",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("Track", "out", "CurrentTrack"),
            arg("TrackDuration", "out", "CurrentTrackDuration"),
            arg("TrackMetaData", "out", "CurrentTrackMetaData"),
            arg("TrackURI", "out", "CurrentTrackURI"),
            arg("RelTime", "out", "RelativeTimePosition"),
            arg("AbsTime", "out", "AbsoluteTimePosition"),
            arg("RelCount", "out", "RelativeCounterPosition"),
            arg("AbsCount", "out", "AbsoluteCounterPosition")) +
        action("GetDeviceCapabilities",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("PlayMedia", "out", "PossiblePlaybackStorageMedia"),
            arg("RecMedia", "out", "PossibleRecordStorageMedia"),
            arg("RecQualityModes", "out", "PossibleRecordQualityModes")) +
        action("GetTransportSettings",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("PlayMode", "out", "CurrentPlayMode"),
            arg("RecQualityMode", "out", "CurrentRecordQualityMode")) +
        action("Stop", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")) +
        action("Play",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("Speed", "in", "TransportPlaySpeed")) +
        action("Pause", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")) +
        action("Seek",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("Unit", "in", "A_ARG_TYPE_SeekMode"),
            arg("Target", "in", "A_ARG_TYPE_SeekTarget")) +
        action("Next", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")) +
        action("Previous", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")) +
        "</actionList>" +
        "<serviceStateTable>" +
        stateVar("TransportState", "string",
            "<allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue>" +
                "<allowedValue>TRANSITIONING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue>" +
                "<allowedValue>NO_MEDIA_PRESENT</allowedValue>") +
        stateVar("TransportStatus", "string",
            "<allowedValue>OK</allowedValue><allowedValue>ERROR_OCCURRED</allowedValue>") +
        stateVar("TransportPlaySpeed", "string") +
        stateVar("CurrentPlayMode", "string",
            "<allowedValue>NORMAL</allowedValue>") +
        stateVar("CurrentTrack", "ui4") +
        stateVar("CurrentTrackDuration", "string") +
        stateVar("CurrentTrackMetaData", "string") +
        stateVar("CurrentTrackURI", "string") +
        stateVar("AVTransportURI", "string") +
        stateVar("AVTransportURIMetaData", "string") +
        stateVar("NextAVTransportURI", "string") +
        stateVar("NextAVTransportURIMetaData", "string") +
        stateVar("RelativeTimePosition", "string") +
        stateVar("AbsoluteTimePosition", "string") +
        stateVar("RelativeCounterPosition", "i4") +
        stateVar("AbsoluteCounterPosition", "i4") +
        stateVar("CurrentMediaDuration", "string") +
        stateVar("NumberOfTracks", "ui4") +
        stateVar("PlaybackStorageMedium", "string") +
        stateVar("RecordStorageMedium", "string") +
        stateVar("WriteStatus", "string") +
        stateVar("PossiblePlaybackStorageMedia", "string") +
        stateVar("PossibleRecordStorageMedia", "string") +
        stateVar("PossibleRecordQualityModes", "string") +
        stateVar("CurrentRecordQualityMode", "string") +
        stateVar("A_ARG_TYPE_InstanceID", "ui4") +
        stateVar("A_ARG_TYPE_SeekMode", "string",
            "<allowedValue>TRACK_NR</allowedValue><allowedValue>REL_TIME</allowedValue>") +
        stateVar("A_ARG_TYPE_SeekTarget", "string") +
        "</serviceStateTable></scpd>"

    private val RC = """<?xml version="1.0" encoding="utf-8"?>""" +
        """<scpd xmlns="urn:schemas-upnp-org:service-1-0">""" +
        "<specVersion><major>1</major><minor>0</minor></specVersion>" +
        "<actionList>" +
        action("GetMute",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("Channel", "in", "A_ARG_TYPE_Channel"),
            arg("CurrentMute", "out", "Mute")) +
        action("SetMute",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("Channel", "in", "A_ARG_TYPE_Channel"),
            arg("DesiredMute", "in", "Mute")) +
        action("GetVolume",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("Channel", "in", "A_ARG_TYPE_Channel"),
            arg("CurrentVolume", "out", "Volume")) +
        action("SetVolume",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("Channel", "in", "A_ARG_TYPE_Channel"),
            arg("DesiredVolume", "in", "Volume")) +
        action("ListPresets",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("CurrentPresetNameList", "out", "PresetNameList")) +
        action("SelectPreset",
            arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"),
            arg("PresetName", "in", "A_ARG_TYPE_PresetName")) +
        "</actionList>" +
        "<serviceStateTable>" +
        stateVar("Mute", "boolean") +
        stateVar("Volume", "ui2") +
        stateVar("PresetNameList", "string") +
        stateVar("A_ARG_TYPE_Channel", "string",
            "<allowedValue>Master</allowedValue>") +
        stateVar("A_ARG_TYPE_InstanceID", "ui4") +
        stateVar("A_ARG_TYPE_PresetName", "string") +
        "</serviceStateTable></scpd>"

    private val CM = """<?xml version="1.0" encoding="utf-8"?>""" +
        """<scpd xmlns="urn:schemas-upnp-org:service-1-0">""" +
        "<specVersion><major>1</major><minor>0</minor></specVersion>" +
        "<actionList>" +
        action("GetProtocolInfo",
            arg("Source", "out", "SourceProtocolInfo"),
            arg("Sink", "out", "SinkProtocolInfo")) +
        action("GetCurrentConnectionIDs",
            arg("ConnectionIDs", "out", "CurrentConnectionIDs")) +
        action("GetCurrentConnectionInfo",
            arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID"),
            arg("RcsID", "out", "A_ARG_TYPE_RcsID"),
            arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID"),
            arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo"),
            arg("PeerConnectionManager", "out", "A_ARG_TYPE_PeerConnectionManager"),
            arg("PeerConnectionID", "out", "A_ARG_TYPE_PeerConnectionID"),
            arg("Direction", "out", "A_ARG_TYPE_Direction"),
            arg("Status", "out", "A_ARG_TYPE_ConnectionStatus")) +
        "</actionList>" +
        "<serviceStateTable>" +
        stateVar("SourceProtocolInfo", "string") +
        stateVar("SinkProtocolInfo", "string") +
        stateVar("CurrentConnectionIDs", "string") +
        stateVar("A_ARG_TYPE_ConnectionID", "i4") +
        stateVar("A_ARG_TYPE_RcsID", "ui4") +
        stateVar("A_ARG_TYPE_AVTransportID", "ui4") +
        stateVar("A_ARG_TYPE_ProtocolInfo", "string") +
        stateVar("A_ARG_TYPE_PeerConnectionManager", "string") +
        stateVar("A_ARG_TYPE_PeerConnectionID", "i4") +
        stateVar("A_ARG_TYPE_Direction", "string",
            "<allowedValue>Input</allowedValue><allowedValue>Output</allowedValue>") +
        stateVar("A_ARG_TYPE_ConnectionStatus", "string",
            "<allowedValue>OK</allowedValue>") +
        "</serviceStateTable></scpd>"

    private fun action(name: String, vararg args: String): String =
        "<action><name>$name</name><argumentList>${args.joinToString("")}</argumentList></action>"

    private fun arg(name: String, direction: String, relatedStateVar: String): String =
        "<argument><name>$name</name><direction>$direction</direction>" +
            "<relatedStateVariable>$relatedStateVar</relatedStateVariable></argument>"

    private fun stateVar(name: String, dataType: String, allowedValues: String = ""): String =
        "<stateVariable sendEvents=\"no\"><name>$name</name>" +
            "<dataType>$dataType</dataType>$allowedValues</stateVariable>"
}
