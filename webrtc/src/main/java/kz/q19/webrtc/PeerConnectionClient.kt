@file:Suppress("unused")

package kz.q19.webrtc

import android.app.Activity
import android.media.AudioManager
import android.util.Log
import kz.q19.domain.model.webrtc.WebRTCIceCandidate
import kz.q19.domain.model.webrtc.WebRTCIceServer
import kz.q19.domain.model.webrtc.WebRTCSessionDescription
import kz.q19.webrtc.core.ProxyVideoSink
import kz.q19.webrtc.core.WebRTCIceConnectionState
import kz.q19.webrtc.core.WebRTCSurfaceView
import kz.q19.webrtc.utils.*
import kz.q19.webrtc.utils.asIceCandidate
import kz.q19.webrtc.utils.convertType
import org.webrtc.*
import org.webrtc.RendererCommon.ScalingType
import java.util.concurrent.Executors

class PeerConnectionClient(
    private val activity: Activity,
    private var localWebRTCSurfaceView: WebRTCSurfaceView? = null,
    private var remoteWebRTCSurfaceView: WebRTCSurfaceView? = null
) {

    companion object {
        private const val TAG = "PeerConnectionClient"

        const val VIDEO_RESOLUTION_WIDTH = 1024
        const val VIDEO_RESOLUTION_HEIGHT = 768
        const val FPS = 24

        const val AUDIO_TRACK_ID = "ARDAMSa0"
        const val VIDEO_TRACK_ID = "ARDAMSv0"

        private const val BPS_IN_KBPS = 1000
    }

    private val executor = Executors.newSingleThreadExecutor()

    private var isMicrophoneEnabled: Boolean = true
    private var isCameraEnabled: Boolean = false

    private var iceServers: List<PeerConnection.IceServer>? = null

    private var sdpMediaConstraints: MediaConstraints? = null

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var eglBase: EglBase? = null

    private val sdpObserver = InnerSdpObserver()

    private var encoderFactory: VideoEncoderFactory? = null
    private var decoderFactory: VideoDecoderFactory? = null

    private var localAudioSource: AudioSource? = null
    private var localVideoSource: VideoSource? = null

    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localMediaStream: MediaStream? = null

    private var localVideoCapturer: VideoCapturer? = null

    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var localSdp: SessionDescription? = null

    private var localVideoSender: RtpSender? = null

    private var isInitiator = false

    private var audioManager: AppRTCAudioManager? = null

    private var remoteVideoScalingType: ScalingType? = null

    private var listener: Listener? = null

    fun createPeerConnection(
        isMicrophoneEnabled: Boolean,
        isCameraEnabled: Boolean,
        iceServers: List<WebRTCIceServer>,
        videoCodecHwAcceleration: Boolean = true,
        listener: Listener
    ) {
        Logger.debug(TAG, "createPeerConnection")

        this.isMicrophoneEnabled = isMicrophoneEnabled
        this.isCameraEnabled = isCameraEnabled

        this.eglBase = EglBase.create()

        this.iceServers = iceServers.map {
            PeerConnection.IceServer.builder(it.url)
                .setUsername(it.username)
                .setPassword(it.credential)
                .createIceServer()
        }

        this.listener = listener

        isInitiator = false
        sdpMediaConstraints = null
        localSdp = null

        sdpMediaConstraints = buildMediaConstraints()

        executor.execute {
            val initializationOptions = PeerConnectionFactory.InitializationOptions
                .builder(activity)
                .setEnableInternalTracer(true)
                .createInitializationOptions()

            PeerConnectionFactory.initialize(initializationOptions)

            val options = PeerConnectionFactory.Options()
            options.disableNetworkMonitor = true

            if (isCameraEnabled) {
                if (videoCodecHwAcceleration) {
                    encoderFactory = DefaultVideoEncoderFactory(
                        eglBase?.eglBaseContext,  /* enableIntelVp8Encoder */
                        true,  /* enableH264HighProfile */
                        true
                    )

                    decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)
                } else {
                    encoderFactory = SoftwareVideoEncoderFactory()
                    decoderFactory = SoftwareVideoDecoderFactory()
                }
            }

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
//                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            peerConnection = peerConnectionFactory?.let { createPeerConnectionInternally(it) }
        }
    }

    private fun buildMediaConstraints(): MediaConstraints {
        val mediaConstraints = MediaConstraints()

        if (isMicrophoneEnabled) {
            mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        } else {
            mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        if (isCameraEnabled) {
            mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        } else {
            mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        return mediaConstraints
    }

    fun setLocalSurfaceView(localWebRTCSurfaceView: WebRTCSurfaceView?) {
        this.localWebRTCSurfaceView = localWebRTCSurfaceView
    }

    fun initLocalCameraStream() {
        Logger.debug(TAG, "initLocalStream")

        if (isCameraEnabled) {
            activity.runOnUiThread {
                localWebRTCSurfaceView?.init(eglBase?.eglBaseContext, null)
                localWebRTCSurfaceView?.setEnableHardwareScaler(true)
                localWebRTCSurfaceView?.setMirror(false)
                localWebRTCSurfaceView?.setZOrderMediaOverlay(true)
                localWebRTCSurfaceView?.setScalingType(ScalingType.SCALE_ASPECT_FIT)
            }
        }
    }

    fun setRemoteSurfaceView(remoteWebRTCSurfaceView: WebRTCSurfaceView?) {
        this.remoteWebRTCSurfaceView = remoteWebRTCSurfaceView
    }

    fun initRemoteCameraStream() {
        if (isCameraEnabled) {
            activity.runOnUiThread {
                remoteWebRTCSurfaceView?.init(eglBase?.eglBaseContext, null)
                remoteWebRTCSurfaceView?.setEnableHardwareScaler(true)
                remoteWebRTCSurfaceView?.setMirror(false)

                remoteVideoScalingType = ScalingType.SCALE_ASPECT_FILL
                remoteWebRTCSurfaceView?.setScalingType(remoteVideoScalingType)
            }
        }
    }

    fun switchScalingType() {
        activity.runOnUiThread {
            remoteVideoScalingType = if (remoteVideoScalingType == ScalingType.SCALE_ASPECT_FILL) {
                ScalingType.SCALE_ASPECT_FIT
            } else {
                ScalingType.SCALE_ASPECT_FILL
            }
            remoteWebRTCSurfaceView?.setScalingType(remoteVideoScalingType)
            listener?.onRemoteScreenScaleChanged(remoteVideoScalingType == ScalingType.SCALE_ASPECT_FILL)
        }
    }

    fun addLocalStreamToPeer() {
        Logger.debug(TAG, "addLocalStreamToPeer")

        localMediaStream = peerConnectionFactory?.createLocalMediaStream("ARDAMS")

        if (isMicrophoneEnabled) {
            localMediaStream?.addTrack(createAudioTrack())
        }

        if (isCameraEnabled) {
            localMediaStream?.addTrack(createVideoTrack())
            findVideoSender()
        }

        if (!localMediaStream?.audioTracks.isNullOrEmpty() || !localMediaStream?.videoTracks.isNullOrEmpty()) {
            peerConnection?.addStream(localMediaStream)

            startAudioManager()
        }
    }

    fun addRemoteStreamToPeer(mediaStream: MediaStream) {
        Logger.debug(TAG, "addRemoteStreamToPeer")

        if (mediaStream.audioTracks.isNotEmpty()) {
            remoteAudioTrack = mediaStream.audioTracks[0]
            remoteAudioTrack?.setEnabled(true)
        }

        if (isCameraEnabled) {
            if (remoteWebRTCSurfaceView == null) {
                throw NullPointerException("Remote SurfaceViewRenderer is null.")
            }

            if (mediaStream.videoTracks.isNotEmpty()) {
                remoteVideoTrack = mediaStream.videoTracks[0]
                remoteVideoTrack?.setEnabled(true)

                val remoteVideoSink = ProxyVideoSink()
                remoteVideoSink.setTarget(remoteWebRTCSurfaceView)
                remoteVideoTrack?.addSink(remoteVideoSink)
            }
        }
    }

    private fun startAudioManager() {
        activity.runOnUiThread {
            audioManager = AppRTCAudioManager.create(activity)
            audioManager?.start { selectedAudioDevice, availableAudioDevices ->
                Logger.debug(TAG, "onAudioManagerDevicesChanged: $availableAudioDevices, selected: $selectedAudioDevice")
            }
        }

        activity.volumeControlStream = AudioManager.STREAM_VOICE_CALL
    }

    private fun createVideoTrack(): VideoTrack? {
        if (localWebRTCSurfaceView == null) {
            throw NullPointerException("Local SurfaceViewRenderer is null.")
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)

        localVideoSource = peerConnectionFactory?.createVideoSource(false)

        localVideoCapturer = createVideoCapturer()
        localVideoCapturer?.initialize(surfaceTextureHelper, activity, localVideoSource?.capturerObserver)
        localVideoCapturer?.startCapture(
            VIDEO_RESOLUTION_WIDTH,
            VIDEO_RESOLUTION_HEIGHT,
            FPS
        )

        localVideoTrack = peerConnectionFactory?.createVideoTrack(VIDEO_TRACK_ID, localVideoSource)
        localVideoTrack?.setEnabled(true)

        val videoSink = ProxyVideoSink()
        videoSink.setTarget(localWebRTCSurfaceView)
        localVideoTrack?.addSink(videoSink)

        return localVideoTrack
    }

    private fun createAudioTrack(): AudioTrack? {
        localAudioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())

        localAudioTrack = peerConnectionFactory?.createAudioTrack(AUDIO_TRACK_ID, localAudioSource)
        localAudioTrack?.setEnabled(true)

        return localAudioTrack
    }

    private fun createVideoCapturer(): VideoCapturer? {
        return if (useCamera2()) {
            createCameraCapturer(Camera2Enumerator(activity))
        } else {
            createCameraCapturer(Camera1Enumerator(true))
        }
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames
        // find the front facing camera and return it.
        deviceNames
            .filter { enumerator.isFrontFacing(it) }
            .mapNotNull { enumerator.createCapturer(it, null) }
            .forEach { return it }
        return null
    }

    private fun useCamera2(): Boolean = Camera2Enumerator.isSupported(activity)

    private fun findVideoSender() {
        peerConnection?.let {
            for (sender in it.senders) {
                if (sender.track()?.kind() == "video") {
                    Logger.debug(TAG, "Found video sender.")
                    localVideoSender = sender
                }
            }
        }
    }

    fun setVideoMaxBitrate(maxBitrateKbps: Int?) {
        executor.execute {
            if (peerConnection == null || localVideoSender == null) {
                return@execute
            }
            Logger.debug(TAG, "Requested max video bitrate: $maxBitrateKbps")
            if (localVideoSender == null) {
                Logger.debug(TAG, "Sender is not ready.")
                return@execute
            }
            val parameters = localVideoSender?.parameters
            if (parameters == null || parameters.encodings.size == 0) {
                Logger.debug(TAG, "RtpParameters are not ready.")
                return@execute
            }
            for (encoding in parameters.encodings) {
                // Null value means no limit.
                encoding.maxBitrateBps =
                    if (maxBitrateKbps == null) null else maxBitrateKbps * BPS_IN_KBPS
            }
            if (!localVideoSender!!.setParameters(parameters)) {
                Logger.debug(TAG, "RtpSender.setParameters failed.")
            }
            Logger.debug(TAG, "Configured max video bitrate to: $maxBitrateKbps")
        }
    }

    fun addRemoteIceCandidate(webRTCIceCandidate: WebRTCIceCandidate) {
        Logger.debug(TAG, "addRemoteIceCandidate() -> webRTCIceCandidate: $webRTCIceCandidate")

        executor.execute {
            peerConnection?.addIceCandidate(webRTCIceCandidate.asIceCandidate())
        }
    }

    fun setRemoteDescription(webRTCSessionDescription: WebRTCSessionDescription) {
        Logger.debug(TAG, "webRTCSessionDescription: $webRTCSessionDescription")

        executor.execute {
            var sdpDescription = CodecUtils.preferCodec(
                webRTCSessionDescription.description,
                CodecUtils.AUDIO_CODEC_OPUS,
                true
            )

            sdpDescription = CodecUtils.preferCodec(
                sdpDescription,
                CodecUtils.VIDEO_CODEC_VP9,
                false
            )

//            sdpDescription = Codec.setStartBitrate(Codec.AUDIO_CODEC_OPUS, false, sdpDescription, 32)

            peerConnection?.setRemoteDescription(
                sdpObserver,
                SessionDescription(webRTCSessionDescription.convertType(), sdpDescription)
            )
        }
    }

    fun createOffer() {
        Logger.debug(TAG, "createOffer")

        executor.execute {
            isInitiator = true
            peerConnection?.createOffer(sdpObserver, sdpMediaConstraints)
        }
    }

    fun createAnswer() {
        Logger.debug(TAG, "createAnswer")

        executor.execute {
            isInitiator = false
            peerConnection?.createAnswer(sdpObserver, sdpMediaConstraints)
        }
    }

    private fun createPeerConnectionInternally(factory: PeerConnectionFactory): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.RELAY

        val peerConnectionObserver = object : PeerConnection.Observer {
            override fun onAddTrack(rtpReceiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                Logger.debug(TAG, "onAddTrack: $rtpReceiver")
            }

            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Logger.debug(TAG, "onSignalingChange: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Logger.debug(TAG, "onIceConnectionChange: $iceConnectionState")

                executor.execute {
                    listener?.onIceConnectionChange(iceConnectionState.asWebRTCIceConnectionState())
                }
            }

            override fun onIceConnectionReceivingChange(b: Boolean) {
                Logger.debug(TAG, "onIceConnectionReceivingChange: $b")
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Logger.debug(TAG, "onIceGatheringChange: $iceGatheringState")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Logger.debug(TAG, "onIceCandidate: $iceCandidate")

                executor.execute {
                    listener?.onIceCandidate(iceCandidate.asWebRTCIceCandidate())
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                Logger.debug(TAG, "onIceCandidatesRemoved: " + iceCandidates.contentToString())
            }

            override fun onAddStream(mediaStream: MediaStream) {
                Logger.debug(TAG, "onAddStream -> audioTracks: ${mediaStream.audioTracks.size}, videoTracks: ${mediaStream.videoTracks.size}")

                executor.execute {
                    listener?.onAddRemoteStream(mediaStream)
                }
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                Logger.debug(TAG, "onRemoveStream: $mediaStream")

                executor.execute {
                    listener?.onRemoveStream(mediaStream)
                }
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Logger.debug(TAG, "onDataChannel: $dataChannel")
            }

            override fun onRenegotiationNeeded() {
                Logger.debug(TAG, "onRenegotiationNeeded")

                executor.execute {
                    listener?.onRenegotiationNeeded()
                }
            }
        }
        return factory.createPeerConnection(rtcConfig, peerConnectionObserver)
    }

    fun onSwitchCamera() {
        executor.execute {
            localVideoCapturer?.let { videoCapturer ->
                if (videoCapturer is CameraVideoCapturer) {
                    videoCapturer.switchCamera(null)
                }
            }
        }
    }

    fun removeListeners() {
        listener = null
    }

    fun dispose() {
        activity.runOnUiThread {
            audioManager?.stop()
            audioManager = null

            Logger.debug(TAG, "Stopping capture.")
            try {
                localVideoCapturer?.stopCapture()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        isInitiator = false
        sdpMediaConstraints = null
        localSdp = null
        isCameraEnabled = false
        isMicrophoneEnabled = false
        remoteVideoScalingType = null
        localVideoSender = null

        executor.execute {
            activity.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE

            peerConnection?.dispose()

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            localVideoCapturer?.dispose()
            localVideoCapturer = null

            localVideoSource?.dispose()
            localVideoSource = null

            localAudioSource?.dispose()
            localAudioSource = null

    //        localMediaStream?.dispose()
            localMediaStream = null

            Logger.debug(TAG, "Closing peer connection factory.")
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null

            Logger.debug(TAG, "Closing peer connection done.")

//            PeerConnectionFactory.stopInternalTracingCapture()
//            PeerConnectionFactory.shutdownInternalTracer()

            try {
                localWebRTCSurfaceView?.release()
                remoteWebRTCSurfaceView?.release()
            } catch (e: Exception) {
                Logger.debug(TAG, "Exception on SurfaceViewRenderer release. $e")
            }

            eglBase?.release()
            eglBase = null

//            localVideoTrack?.dispose()
            localVideoTrack = null
//            remoteVideoTrack?.dispose()
            remoteVideoTrack = null

//            localAudioTrack?.dispose()
            localAudioTrack = null

//            remoteAudioTrack?.dispose()
            remoteAudioTrack = null

            peerConnection = null
        }
    }

    private fun reportError(errorMessage: String) {
        Log.e(TAG, "PeerConnection error: $errorMessage")
        executor.execute {
            listener?.onPeerConnectionError(errorMessage)
        }
    }

    private inner class InnerSdpObserver : SdpObserver {

        override fun onCreateSuccess(sessionDescription: SessionDescription?) {
            Logger.debug(TAG, "onCreateSuccess: $sessionDescription")

            if (sessionDescription == null) return

            if (localSdp != null) {
                reportError("Multiple SDP create.")
                return
            }

            var sdpDescription = CodecUtils.preferCodec(
                sessionDescription.description,
                CodecUtils.AUDIO_CODEC_OPUS,
                true
            )

            sdpDescription = CodecUtils.preferCodec(
                sdpDescription,
                CodecUtils.VIDEO_CODEC_VP9,
                false
            )

            localSdp = SessionDescription(sessionDescription.type, sdpDescription)

            executor.execute {
                Logger.debug(TAG, "Set local SDP from " + localSdp?.type)
                peerConnection?.setLocalDescription(sdpObserver, localSdp)
            }
        }

        override fun onSetSuccess() {
            Logger.debug(TAG, "onSetSuccess")

            executor.execute {
                if (isInitiator) {
                    // For offering peer connection we first create offer and set
                    // local SDP, then after receiving answer set remote SDP.
                    if (peerConnection?.remoteDescription == null) {
                        // We've just set our local SDP so time to send it.
                        Logger.debug(TAG, "Local SDP set successfully")
                        localSdp?.let {
                            listener?.onLocalDescription(it.asWebRTCSessionDescription())
                        }
                    } else {
                        // We've just set remote description, so drain remote
                        // and send local ICE candidates.
                        Logger.debug(TAG, "Remote SDP set successfully")
                    }
                } else {
                    // For answering peer connection we set remote SDP and then
                    // create answer and set local SDP.
                    if (peerConnection?.localDescription != null) {
                        // We've just set our local SDP so time to send it, drain
                        // remote and send local ICE candidates.
                        Logger.debug(TAG, "Local SDP set successfully")
                        localSdp?.let {
                            listener?.onLocalDescription(it.asWebRTCSessionDescription())
                        }
                    } else {
                        // We've just set remote SDP - do nothing for now -
                        // answer will be created soon.
                        Logger.debug(TAG, "Remote SDP set successfully")
                    }
                }
            }
        }

        override fun onCreateFailure(error: String?) {
            Logger.debug(TAG, "onCreateFailure: $error")

            reportError("Create SDP error: $error")
        }

        override fun onSetFailure(error: String?) {
            Logger.debug(TAG, "onSetFailure: $error")

            reportError("Set SDP error: $error")
        }
    }

    interface Listener {
        fun onIceCandidate(webRTCIceCandidate: WebRTCIceCandidate)
        fun onIceConnectionChange(webRTCIceConnectionState: WebRTCIceConnectionState)
        fun onRenegotiationNeeded()
        fun onLocalDescription(webRTCSessionDescription: WebRTCSessionDescription)

        fun onAddRemoteStream(mediaStream: MediaStream)
        fun onRemoveStream(mediaStream: MediaStream)

        fun onPeerConnectionError(errorMessage: String)

        fun onRemoteScreenScaleChanged(isFilled: Boolean)
    }

}
