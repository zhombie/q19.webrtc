package kz.q19.webrtc

import kz.q19.domain.model.webrtc.IceServer

data class Options constructor(
    var isLocalAudioEnabled: Boolean = LOCAL_AUDIO_ENABLED,
    var isLocalVideoEnabled: Boolean = LOCAL_VIDEO_ENABLED,
    var isRemoteAudioEnabled: Boolean = REMOTE_AUDIO_ENABLED,
    var isRemoteVideoEnabled: Boolean = REMOTE_VIDEO_ENABLED,

    var iceServers: List<IceServer> = emptyList(),

    var videoCodecHwAcceleration: Boolean = true,

    var localVideoWidth: Int = VIDEO_RESOLUTION_WIDTH,
    var localVideoHeight: Int = VIDEO_RESOLUTION_HEIGHT,
    var localVideoFPS: Int = FPS,

    var localAudioTrackId: String = AUDIO_TRACK_ID,
    var localVideoTrackId: String = VIDEO_TRACK_ID,

    var bpsInKbps: Int = BPS_IN_KBPS
) {

    companion object {
        const val LOCAL_AUDIO_ENABLED: Boolean = false
        const val REMOTE_AUDIO_ENABLED: Boolean = false
        const val LOCAL_VIDEO_ENABLED: Boolean = false
        const val REMOTE_VIDEO_ENABLED: Boolean = false

        const val VIDEO_RESOLUTION_WIDTH: Int = 1024
        const val VIDEO_RESOLUTION_HEIGHT: Int = 768
        const val FPS: Int = 30

        const val AUDIO_TRACK_ID: String = "ARDAMSa0"
        const val VIDEO_TRACK_ID: String = "ARDAMSv0"

        const val BPS_IN_KBPS: Int = 1000
    }

}