package kz.q19.webrtc.utils

import kz.q19.webrtc.core.WebRTCIceConnectionState
import org.webrtc.PeerConnection

internal fun PeerConnection.IceConnectionState.asWebRTCIceConnectionState(): WebRTCIceConnectionState {
    return when (this) {
        PeerConnection.IceConnectionState.NEW -> WebRTCIceConnectionState.NEW
        PeerConnection.IceConnectionState.CHECKING -> WebRTCIceConnectionState.CHECKING
        PeerConnection.IceConnectionState.CONNECTED -> WebRTCIceConnectionState.CONNECTED
        PeerConnection.IceConnectionState.COMPLETED -> WebRTCIceConnectionState.COMPLETED
        PeerConnection.IceConnectionState.FAILED -> WebRTCIceConnectionState.FAILED
        PeerConnection.IceConnectionState.DISCONNECTED -> WebRTCIceConnectionState.DISCONNECTED
        PeerConnection.IceConnectionState.CLOSED -> WebRTCIceConnectionState.CLOSED
    }
}