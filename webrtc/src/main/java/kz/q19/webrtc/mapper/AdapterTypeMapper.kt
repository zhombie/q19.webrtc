package kz.q19.webrtc.mapper

import kz.q19.domain.model.webrtc.AdapterType
import org.webrtc.PeerConnection

internal class AdapterTypeMapper {

    companion object {
        fun map(adapterType: PeerConnection.AdapterType): AdapterType {
            return when (adapterType) {
                PeerConnection.AdapterType.UNKNOWN -> AdapterType.UNKNOWN
                PeerConnection.AdapterType.ETHERNET -> AdapterType.ETHERNET
                PeerConnection.AdapterType.WIFI -> AdapterType.WIFI
                PeerConnection.AdapterType.CELLULAR -> AdapterType.CELLULAR
                PeerConnection.AdapterType.VPN -> AdapterType.VPN
                PeerConnection.AdapterType.LOOPBACK -> AdapterType.LOOPBACK
                PeerConnection.AdapterType.ADAPTER_TYPE_ANY -> AdapterType.ADAPTER_TYPE_ANY
                else -> AdapterType.UNKNOWN
            }
        }

        fun map(adapterType: AdapterType): PeerConnection.AdapterType {
            return when (adapterType) {
                AdapterType.UNKNOWN -> PeerConnection.AdapterType.UNKNOWN
                AdapterType.ETHERNET -> PeerConnection.AdapterType.ETHERNET
                AdapterType.WIFI -> PeerConnection.AdapterType.WIFI
                AdapterType.CELLULAR -> PeerConnection.AdapterType.CELLULAR
                AdapterType.VPN -> PeerConnection.AdapterType.VPN
                AdapterType.LOOPBACK -> PeerConnection.AdapterType.LOOPBACK
                AdapterType.ADAPTER_TYPE_ANY -> PeerConnection.AdapterType.ADAPTER_TYPE_ANY
            }
        }
    }

}