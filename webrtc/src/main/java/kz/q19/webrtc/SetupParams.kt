package kz.q19.webrtc

import kz.q19.domain.model.webrtc.WebRTCIceServer

data class SetupParams(
    var isLocalAudioEnabled: Boolean = Configs.LOCAL_AUDIO_ENABLED,
    var isLocalVideoEnabled: Boolean = Configs.LOCAL_VIDEO_ENABLED,
    var isRemoteAudioEnabled: Boolean = Configs.REMOTE_AUDIO_ENABLED,
    var isRemoteVideoEnabled: Boolean = Configs.REMOTE_VIDEO_ENABLED,
    var iceServers: List<WebRTCIceServer> = emptyList(),
    var videoCodecHwAcceleration: Boolean = true
)