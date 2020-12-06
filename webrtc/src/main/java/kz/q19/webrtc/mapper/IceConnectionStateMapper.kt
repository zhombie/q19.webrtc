package kz.q19.webrtc.mapper

import kz.q19.domain.model.webrtc.IceConnectionState
import org.webrtc.PeerConnection

internal class IceConnectionStateMapper {

    companion object {
        fun map(iceConnectionState: PeerConnection.IceConnectionState): IceConnectionState {
            return when (iceConnectionState) {
                PeerConnection.IceConnectionState.NEW -> IceConnectionState.NEW
                PeerConnection.IceConnectionState.CHECKING -> IceConnectionState.CHECKING
                PeerConnection.IceConnectionState.CONNECTED -> IceConnectionState.CONNECTED
                PeerConnection.IceConnectionState.COMPLETED -> IceConnectionState.COMPLETED
                PeerConnection.IceConnectionState.FAILED -> IceConnectionState.FAILED
                PeerConnection.IceConnectionState.DISCONNECTED -> IceConnectionState.DISCONNECTED
                PeerConnection.IceConnectionState.CLOSED -> IceConnectionState.CLOSED
            }
        }

        fun map(iceConnectionState: IceConnectionState): PeerConnection.IceConnectionState {
            return when (iceConnectionState) {
                IceConnectionState.NEW -> PeerConnection.IceConnectionState.NEW
                IceConnectionState.CHECKING -> PeerConnection.IceConnectionState.CHECKING
                IceConnectionState.CONNECTED -> PeerConnection.IceConnectionState.CONNECTED
                IceConnectionState.COMPLETED -> PeerConnection.IceConnectionState.COMPLETED
                IceConnectionState.FAILED -> PeerConnection.IceConnectionState.FAILED
                IceConnectionState.DISCONNECTED -> PeerConnection.IceConnectionState.DISCONNECTED
                IceConnectionState.CLOSED -> PeerConnection.IceConnectionState.CLOSED
            }
        }
    }

}