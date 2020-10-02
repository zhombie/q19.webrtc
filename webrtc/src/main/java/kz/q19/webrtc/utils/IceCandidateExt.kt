package kz.q19.webrtc.utils

import kz.q19.domain.model.webrtc.WebRTCAdapterType
import kz.q19.domain.model.webrtc.WebRTCIceCandidate
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

internal fun WebRTCIceCandidate.asIceCandidate(): IceCandidate {
    return IceCandidate(
        sdpMid,
        sdpMLineIndex,
        sdp
    )
}

internal fun WebRTCIceCandidate.convertAdapterType(): PeerConnection.AdapterType {
    return when (adapterType) {
        WebRTCAdapterType.UNKNOWN -> PeerConnection.AdapterType.UNKNOWN
        WebRTCAdapterType.ETHERNET -> PeerConnection.AdapterType.ETHERNET
        WebRTCAdapterType.WIFI -> PeerConnection.AdapterType.WIFI
        WebRTCAdapterType.CELLULAR -> PeerConnection.AdapterType.CELLULAR
        WebRTCAdapterType.VPN -> PeerConnection.AdapterType.VPN
        WebRTCAdapterType.LOOPBACK -> PeerConnection.AdapterType.LOOPBACK
        WebRTCAdapterType.ADAPTER_TYPE_ANY -> PeerConnection.AdapterType.ADAPTER_TYPE_ANY
    }
}

internal fun IceCandidate.asWebRTCIceCandidate(): WebRTCIceCandidate {
    return WebRTCIceCandidate(
        sdpMid = sdpMid,
        sdpMLineIndex = sdpMLineIndex,
        sdp = sdp,
        serverUrl = serverUrl,
        adapterType = convertAdapterType()
    )
}

internal fun IceCandidate.convertAdapterType(): WebRTCAdapterType {
    return when (adapterType) {
        PeerConnection.AdapterType.UNKNOWN -> WebRTCAdapterType.UNKNOWN
        PeerConnection.AdapterType.ETHERNET -> WebRTCAdapterType.ETHERNET
        PeerConnection.AdapterType.WIFI -> WebRTCAdapterType.WIFI
        PeerConnection.AdapterType.CELLULAR -> WebRTCAdapterType.CELLULAR
        PeerConnection.AdapterType.VPN -> WebRTCAdapterType.VPN
        PeerConnection.AdapterType.LOOPBACK -> WebRTCAdapterType.LOOPBACK
        PeerConnection.AdapterType.ADAPTER_TYPE_ANY -> WebRTCAdapterType.ADAPTER_TYPE_ANY
        else -> WebRTCAdapterType.UNKNOWN
    }
}