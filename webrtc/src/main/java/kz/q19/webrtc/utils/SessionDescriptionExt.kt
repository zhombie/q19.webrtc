package kz.q19.webrtc.utils

import kz.q19.domain.model.webrtc.WebRTCSessionDescription
import org.webrtc.SessionDescription

internal fun WebRTCSessionDescription.asSessionDescription(): SessionDescription {
    return SessionDescription(convertType(), description)
}

internal fun WebRTCSessionDescription.convertType(): SessionDescription.Type {
    return when (type) {
        WebRTCSessionDescription.Type.ANSWER -> SessionDescription.Type.ANSWER
        WebRTCSessionDescription.Type.OFFER -> SessionDescription.Type.OFFER
    }
}

internal fun SessionDescription.asWebRTCSessionDescription(): WebRTCSessionDescription {
    return WebRTCSessionDescription(
        convertType(),
        this.description
    )
}

internal fun SessionDescription.convertType(): WebRTCSessionDescription.Type {
    return when (type) {
        SessionDescription.Type.OFFER -> WebRTCSessionDescription.Type.OFFER
        SessionDescription.Type.ANSWER -> WebRTCSessionDescription.Type.ANSWER
        else -> throw IllegalStateException("WebRTC. Neither OFFER or ANSWER")
    }
}