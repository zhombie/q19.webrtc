package kz.q19.webrtc.mapper

import kz.q19.domain.model.webrtc.SessionDescription

internal class SessionDescriptionMapper {

    companion object {
        fun map(sessionDescription: org.webrtc.SessionDescription): SessionDescription {
            return SessionDescription(map(sessionDescription.type), sessionDescription.description)
        }

        fun map(sessionDescription: SessionDescription): org.webrtc.SessionDescription {
            return org.webrtc.SessionDescription(map(sessionDescription.type), sessionDescription.description)
        }

        fun map(type: SessionDescription.Type): org.webrtc.SessionDescription.Type {
            return when (type) {
                SessionDescription.Type.ANSWER -> org.webrtc.SessionDescription.Type.ANSWER
                SessionDescription.Type.OFFER -> org.webrtc.SessionDescription.Type.OFFER
            }
        }

        fun map(type: org.webrtc.SessionDescription.Type): SessionDescription.Type {
            return when (type) {
                org.webrtc.SessionDescription.Type.ANSWER -> SessionDescription.Type.ANSWER
                org.webrtc.SessionDescription.Type.OFFER -> SessionDescription.Type.OFFER
                else -> throw IllegalStateException("WebRTC. Neither OFFER or ANSWER")
            }
        }
    }

}