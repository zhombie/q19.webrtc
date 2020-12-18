@file:Suppress("unused")

package kz.q19.webrtc

import android.app.Activity
import android.media.AudioManager
import kz.q19.domain.model.webrtc.IceConnectionState
import kz.q19.webrtc.core.ProxyVideoSink
import kz.q19.webrtc.core.SurfaceViewRenderer
import kz.q19.webrtc.mapper.*
import kz.q19.webrtc.mapper.AdapterTypeMapper
import kz.q19.webrtc.mapper.IceCandidateMapper
import kz.q19.webrtc.mapper.IceConnectionStateMapper
import kz.q19.webrtc.mapper.ScalingTypeMapper
import kz.q19.webrtc.mapper.SessionDescriptionMapper
import kz.q19.webrtc.utils.*
import org.webrtc.*
import org.webrtc.RendererCommon.ScalingType
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PeerConnectionClient(
    private val activity: Activity,
    private var localSurfaceViewRenderer: SurfaceViewRenderer? = null,
    private var remoteSurfaceViewRenderer: SurfaceViewRenderer? = null
) {

    companion object {
        private val TAG = PeerConnectionClient::class.java.simpleName
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var localVideoWidth: Int = Configs.VIDEO_RESOLUTION_WIDTH
    private var localVideoHeight: Int = Configs.VIDEO_RESOLUTION_HEIGHT
    private var localVideoFPS: Int = Configs.FPS

    private var isLocalAudioEnabled: Boolean = Configs.LOCAL_AUDIO_ENABLED
    private var isRemoteAudioEnabled: Boolean = Configs.REMOTE_AUDIO_ENABLED
    private var isLocalVideoEnabled: Boolean = Configs.LOCAL_VIDEO_ENABLED
    private var isRemoteVideoEnabled: Boolean = Configs.REMOTE_VIDEO_ENABLED

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
    private var remoteMediaStream: MediaStream? = null

    private var localVideoCapturer: VideoCapturer? = null

    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var localVideoSink: ProxyVideoSink? = null
    private var remoteVideoSink: ProxyVideoSink? = null

    private var localSessionDescription: SessionDescription? = null

    private var localVideoSender: RtpSender? = null

    private var isSwappedFeeds: Boolean = false

    private var isInitiator = false

    var audioManager: AppRTCAudioManager? = null

    private var remoteVideoScalingType: ScalingType? = null

    private var listener: Listener? = null

    fun createPeerConnection(
        setupParams: SetupParams,
        listener: Listener? = null
    ): PeerConnection? {
        Logger.debug(TAG, "createPeerConnection() -> $setupParams, $listener")

        isLocalAudioEnabled = setupParams.isLocalAudioEnabled
        isLocalVideoEnabled = setupParams.isLocalVideoEnabled
        isRemoteAudioEnabled = setupParams.isRemoteAudioEnabled
        isRemoteVideoEnabled = setupParams.isRemoteVideoEnabled

        eglBase = EglBase.create()

        if (setupParams.iceServers.any { it.url.isNullOrBlank() || it.urls.isNullOrBlank() }) {
            iceServers = emptyList()
        } else {
            iceServers = setupParams.iceServers.map {
                val builder = if (!it.url.isNullOrBlank()) {
                    PeerConnection.IceServer.builder(it.url)
                } else if (!it.urls.isNullOrBlank()) {
                    PeerConnection.IceServer.builder(it.urls)
                } else {
                    throw IllegalStateException("url || urls is null or blank. Please provide anything.")
                }
                builder.setUsername(it.username ?: "")
                builder.setPassword(it.credential ?: "")
                builder.createIceServer()
            }
        }

        Logger.debug(TAG, "iceServers: $iceServers")

        this.listener = listener

        isInitiator = false
        sdpMediaConstraints = null
        localSessionDescription = null

        sdpMediaConstraints = buildMediaConstraints()

        val future = executor.submit(Callable {
            val initializationOptions = PeerConnectionFactory.InitializationOptions
                .builder(activity)
                .setEnableInternalTracer(true)
                .createInitializationOptions()

            PeerConnectionFactory.initialize(initializationOptions)

            val options = PeerConnectionFactory.Options()
            options.disableNetworkMonitor = true

            if (isLocalVideoEnabled) {
                if (setupParams.videoCodecHwAcceleration) {
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

            return@Callable peerConnection
        })

        return future.get()
    }

    private fun buildMediaConstraints(): MediaConstraints {
        Logger.debug(TAG, "buildMediaConstraints()")

        val mediaConstraints = MediaConstraints()

        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))

        if (isLocalVideoEnabled || isRemoteVideoEnabled) {
            mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        } else {
            mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("levelControl", "true"))

        return mediaConstraints
    }

    fun setLocalSurfaceView(localSurfaceView: SurfaceViewRenderer?) {
        Logger.debug(TAG, "setLocalSurfaceView() -> $localSurfaceView")

        this.localSurfaceViewRenderer = localSurfaceView
    }

    fun initLocalCameraStream(isMirrored: Boolean = false) {
        Logger.debug(TAG, "initLocalCameraStream() -> isMirrored: $isMirrored")

        if (isLocalVideoEnabled) {
            if (localSurfaceViewRenderer == null) {
                throw NullPointerException("Local SurfaceViewRenderer is null.")
            }

            activity.runOnUiThread {
                localSurfaceViewRenderer?.init(eglBase?.eglBaseContext, null)
                localSurfaceViewRenderer?.setEnableHardwareScaler(true)
                localSurfaceViewRenderer?.setMirror(isMirrored)
                localSurfaceViewRenderer?.setZOrderMediaOverlay(true)
                localSurfaceViewRenderer?.setScalingType(ScalingType.SCALE_ASPECT_FIT)
            }
        }
    }

    fun setRemoteSurfaceView(remoteSurfaceView: SurfaceViewRenderer?) {
        Logger.debug(TAG, "setRemoteSurfaceView() -> $remoteSurfaceView")

        this.remoteSurfaceViewRenderer = remoteSurfaceView
    }

    fun initRemoteCameraStream(isMirrored: Boolean = false) {
        Logger.debug(TAG, "initRemoteCameraStream() -> isMirrored: $isMirrored")

        if (isRemoteVideoEnabled) {
            if (remoteSurfaceViewRenderer == null) {
                throw NullPointerException("Local SurfaceViewRenderer is null.")
            }

            activity.runOnUiThread {
                remoteSurfaceViewRenderer?.init(eglBase?.eglBaseContext, null)
                remoteSurfaceViewRenderer?.setEnableHardwareScaler(true)
                remoteSurfaceViewRenderer?.setMirror(isMirrored)

                remoteVideoScalingType = ScalingType.SCALE_ASPECT_FILL
                remoteSurfaceViewRenderer?.setScalingType(remoteVideoScalingType)
            }
        }
    }

    fun setScalingType(scalingType: kz.q19.webrtc.core.ScalingType) {
        return activity.runOnUiThread {
            remoteVideoScalingType = ScalingTypeMapper.map(scalingType)
            remoteSurfaceViewRenderer?.setScalingType(remoteVideoScalingType)
        }
    }

    fun addLocalStreamToPeer() {
        Logger.debug(TAG, "addLocalStreamToPeer()")

        localMediaStream = peerConnectionFactory?.createLocalMediaStream("ARDAMS")

        if (isLocalAudioEnabled) {
            localMediaStream?.addTrack(createAudioTrack())
        }

        if (isLocalVideoEnabled) {
            localMediaStream?.addTrack(createVideoTrack())
            findVideoSender()
        }

        if (localMediaStream != null) {
            peerConnection?.addStream(localMediaStream)
        }

        startAudioManager()
    }

    fun addRemoteStreamToPeer(mediaStream: MediaStream) {
        Logger.debug(TAG, "addRemoteStreamToPeer() -> mediaStream: $mediaStream")

        try {
            val id = mediaStream.id
            Logger.debug(TAG, "addRemoteStreamToPeer() [MediaStream exists] -> id: $id")
        } catch (e: IllegalStateException) {
            Logger.debug(TAG, "addRemoteStreamToPeer() [MediaStream does not exist]")
            return
        }

        remoteMediaStream = mediaStream

        if (mediaStream.audioTracks.isNotEmpty()) {
            remoteAudioTrack = mediaStream.audioTracks.first()
            remoteAudioTrack?.setEnabled(isRemoteAudioEnabled)
        }

        if (remoteSurfaceViewRenderer == null) {
            throw NullPointerException("Remote SurfaceViewRenderer is null.")
        }

        if (mediaStream.videoTracks.isNotEmpty()) {
            remoteVideoTrack = mediaStream.videoTracks.first()
            remoteVideoTrack?.setEnabled(isRemoteVideoEnabled)

            remoteVideoSink = ProxyVideoSink()
            remoteVideoSink?.setTarget(remoteSurfaceViewRenderer)
            remoteVideoTrack?.addSink(remoteVideoSink)
        }
    }

    private fun startAudioManager() {
        Logger.debug(TAG, "startAudioManager()")

        activity.runOnUiThread {
            audioManager = AppRTCAudioManager.create(activity)
            audioManager?.start { selectedAudioDevice, availableAudioDevices ->
                Logger.debug(TAG, "audioManager: $availableAudioDevices, $selectedAudioDevice")
            }
        }

        activity.volumeControlStream = AudioManager.STREAM_VOICE_CALL
    }

    private fun createVideoTrack(): VideoTrack? {
        Logger.debug(TAG, "createVideoTrack()")

        if (localSurfaceViewRenderer == null) {
            throw NullPointerException("Local SurfaceViewRenderer is null.")
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)

        localVideoSource = peerConnectionFactory?.createVideoSource(false)

        localVideoCapturer = createVideoCapturer()

        localVideoCapturer?.initialize(surfaceTextureHelper, activity, localVideoSource?.capturerObserver)

        localVideoCapturer?.startCapture(localVideoWidth, localVideoHeight, localVideoFPS)

        localVideoTrack = peerConnectionFactory?.createVideoTrack(Configs.VIDEO_TRACK_ID, localVideoSource)
        localVideoTrack?.setEnabled(isLocalVideoEnabled)

        localVideoSink = ProxyVideoSink()
        localVideoSink?.setTarget(localSurfaceViewRenderer)
        localVideoTrack?.addSink(localVideoSink)

        return localVideoTrack
    }

    private fun createAudioTrack(): AudioTrack? {
        Logger.debug(TAG, "createAudioTrack()")

        localAudioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())

        localAudioTrack = peerConnectionFactory?.createAudioTrack(Configs.AUDIO_TRACK_ID, localAudioSource)
        localAudioTrack?.setEnabled(isLocalAudioEnabled)

        return localAudioTrack
    }

    private fun createVideoCapturer(): VideoCapturer? {
        Logger.debug(TAG, "createVideoCapturer()")

        return if (useCamera2()) {
            createCameraCapturer(Camera2Enumerator(activity))
        } else {
            createCameraCapturer(Camera1Enumerator(true))
        }
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        Logger.debug(TAG, "createCameraCapturer() -> enumerator: $enumerator")

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
        Logger.debug(TAG, "findVideoSender()")

        peerConnection?.let {
            for (sender in it.senders) {
                if (sender.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    Logger.debug(TAG, "Found video sender.")
                    localVideoSender = sender
                }
            }
        }
    }

    fun setVideoMaxBitrate(maxBitrateKbps: Int?) {
        Logger.debug(TAG, "setVideoMaxBitrate() -> maxBitrateKbps: $maxBitrateKbps")

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
                    if (maxBitrateKbps == null) null else maxBitrateKbps * Configs.BPS_IN_KBPS
            }
            if (!localVideoSender!!.setParameters(parameters)) {
                Logger.debug(TAG, "RtpSender.setParameters failed.")
            }
            Logger.debug(TAG, "Configured max video bitrate to: $maxBitrateKbps")
        }
    }

    fun addRemoteIceCandidate(iceCandidate: kz.q19.domain.model.webrtc.IceCandidate) {
        Logger.debug(TAG, "addRemoteIceCandidate() -> iceCandidate: $iceCandidate")

        executor.execute {
            peerConnection?.addIceCandidate(IceCandidateMapper.map(iceCandidate))
        }
    }

    fun setRemoteDescription(sessionDescription: kz.q19.domain.model.webrtc.SessionDescription) {
        Logger.debug(TAG, "setRemoteDescription() -> sdp: $sessionDescription")

        executor.execute {
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

//            sdpDescription = Codec.setStartBitrate(Codec.AUDIO_CODEC_OPUS, false, sdpDescription, 32)

            peerConnection?.setRemoteDescription(
                sdpObserver,
                SessionDescription(SessionDescriptionMapper.map(sessionDescription.type), sdpDescription)
            )
        }
    }

    fun createOffer() {
        Logger.debug(TAG, "createOffer()")

        executor.execute {
            isInitiator = true
            peerConnection?.createOffer(sdpObserver, sdpMediaConstraints)
        }
    }

    fun createAnswer() {
        Logger.debug(TAG, "createAnswer()")

        executor.execute {
            isInitiator = false
            peerConnection?.createAnswer(sdpObserver, sdpMediaConstraints)
        }
    }

    private fun createPeerConnectionInternally(factory: PeerConnectionFactory): PeerConnection? {
        Logger.debug(TAG, "createPeerConnectionInternally() -> factory: $factory")

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers ?: emptyList())

        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.RELAY

        val peerConnectionObserver = object : PeerConnection.Observer {
            override fun onAddTrack(
                rtpReceiver: RtpReceiver?,
                mediaStreams: Array<out MediaStream>?
            ) {
                Logger.debug(TAG, "onAddTrack() -> $rtpReceiver, ${mediaStreams.contentToString()}")
            }

            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Logger.debug(TAG, "onSignalingChange() -> $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Logger.debug(TAG, "onIceConnectionChange() -> $iceConnectionState")

                executor.execute {
                    listener?.onIceConnectionChange(IceConnectionStateMapper.map(iceConnectionState))
                }
            }

            override fun onIceConnectionReceivingChange(b: Boolean) {
                Logger.debug(TAG, "onIceConnectionReceivingChange() -> b: $b")
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Logger.debug(TAG, "onIceGatheringChange() -> iceGatheringState: $iceGatheringState")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Logger.debug(TAG, "onIceCandidate() -> iceCandidate: $iceCandidate")

                executor.execute {
                    listener?.onIceCandidate(
                        IceCandidateMapper.map(iceCandidate, AdapterTypeMapper.map(iceCandidate.adapterType))
                    )
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                Logger.debug(TAG, "onIceCandidatesRemoved() -> ${iceCandidates.contentToString()}")
            }

            override fun onAddStream(mediaStream: MediaStream) {
                Logger.debug(TAG, "onAddStream() -> mediaStream: $mediaStream")

                executor.execute {
                    listener?.onAddRemoteStream(mediaStream)
                }
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                Logger.debug(TAG, "onRemoveStream() -> mediaStream: $mediaStream")

                executor.execute {
                    listener?.onRemoveStream(mediaStream)
                }
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Logger.debug(TAG, "onDataChannel() -> dataChannel: $dataChannel")
            }

            override fun onRenegotiationNeeded() {
                Logger.debug(TAG, "onRenegotiationNeeded()")

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

    fun setLocalAudioEnabled(isEnabled: Boolean) {
        executor.execute {
            isLocalAudioEnabled = isEnabled
            localAudioTrack?.setEnabled(isLocalAudioEnabled)
        }
    }

    fun setRemoteAudioEnabled(isEnabled: Boolean) {
        executor.execute {
            isRemoteAudioEnabled = isEnabled
            remoteAudioTrack?.setEnabled(isRemoteAudioEnabled)
        }
    }

    fun setLocalVideoEnabled(isEnabled: Boolean) {
        executor.execute {
            isLocalVideoEnabled = isEnabled
            localVideoTrack?.setEnabled(isLocalVideoEnabled)
        }
    }

    fun setRemoteVideoEnabled(isEnabled: Boolean) {
        executor.execute {
            isRemoteVideoEnabled = isEnabled
            remoteVideoTrack?.setEnabled(isRemoteVideoEnabled)
        }
    }

    fun addStream(mediaStream: MediaStream) {
        peerConnection?.addStream(mediaStream)
    }

    fun removeStream(mediaStream: MediaStream) {
        peerConnection?.removeStream(mediaStream)
    }

    fun removeMediaStreamTrack(mediaStreamTrack: MediaStreamTrack) {
        if (mediaStreamTrack.kind() == MediaStreamTrack.AUDIO_TRACK_KIND) {
            remoteMediaStream?.removeTrack(remoteAudioTrack)
        } else if (mediaStreamTrack.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
            remoteMediaStream?.removeTrack(remoteVideoTrack)
        }
    }

    fun setLocalAudioTrackVolume(volume: Double) {
        localAudioTrack?.setVolume(volume)
    }

    fun setRemoteAudioTrackVolume(volume: Double) {
        remoteAudioTrack?.setVolume(volume)
    }

    fun setLocalVideoResolutionWidth(width: Int) {
        localVideoWidth = width
    }

    fun setLocalVideoResolutionHeight(height: Int) {
        localVideoHeight = height
    }

    fun isHDLocalVideo(): Boolean {
        return isLocalVideoEnabled && localVideoWidth * localVideoHeight >= 1280 * 720
    }

    fun setLocalTextureSize(textureWidth: Int, textureHeight: Int) {
        surfaceTextureHelper?.setTextureSize(textureWidth, textureHeight)
    }

    fun changeCaptureFormat(width: Int, height: Int, fps: Int) {
        executor.execute {
            changeCaptureFormatInternal(width, height, fps)
        }
    }

    private fun changeCaptureFormatInternal(width: Int, height: Int, fps: Int) {
        Logger.debug(TAG, "changeCaptureFormat: " + width + "x" + height + "@" + fps)
        localVideoSource?.adaptOutputFormat(width, height, fps)
    }

    fun setSwappedFeeds(isSwappedFeeds: Boolean) {
        Logger.debug(TAG, "setSwappedFeeds() -> isSwappedFeeds: $isSwappedFeeds")

        this.isSwappedFeeds = isSwappedFeeds

        localVideoSink?.setTarget(if (isSwappedFeeds) remoteSurfaceViewRenderer else localSurfaceViewRenderer)
        remoteVideoSink?.setTarget(if (isSwappedFeeds) localSurfaceViewRenderer else remoteSurfaceViewRenderer)
    }

    fun pauseLocalVideoStream() {
        localSurfaceViewRenderer?.pauseVideo()
    }

    fun pauseRemoteVideoStream() {
        remoteSurfaceViewRenderer?.pauseVideo()
    }

    fun startLocalVideoCapture() {
        localVideoCapturer?.startCapture(localVideoWidth, localVideoHeight, localVideoFPS)
    }

    fun stopLocalVideoCapture() {
        localVideoCapturer?.stopCapture()
    }

    fun getAudioOutputDevices(): Set<AppRTCAudioManager.AudioDevice>? = audioManager?.audioDevices

    fun getSelectedAudioOutputDevice(): AppRTCAudioManager.AudioDevice? = audioManager?.selectedAudioDevice

    fun selectAudioOutputSpeakerPhone() {
        selectAudioDeviceInternally(AppRTCAudioManager.AudioDevice.SPEAKER_PHONE)
    }

    fun selectAudioOutputEarpiece() {
        selectAudioDeviceInternally(AppRTCAudioManager.AudioDevice.EARPIECE)
    }

    private fun selectAudioDeviceInternally(audioDevice: AppRTCAudioManager.AudioDevice) {
        audioManager?.selectAudioDevice(audioDevice)
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

        localSessionDescription = null

        isLocalAudioEnabled = Configs.LOCAL_AUDIO_ENABLED
        isLocalVideoEnabled = Configs.LOCAL_VIDEO_ENABLED
        isRemoteAudioEnabled = Configs.REMOTE_AUDIO_ENABLED
        isRemoteVideoEnabled = Configs.REMOTE_VIDEO_ENABLED

        localVideoWidth = Configs.VIDEO_RESOLUTION_WIDTH
        localVideoHeight = Configs.VIDEO_RESOLUTION_HEIGHT
        localVideoFPS = Configs.FPS

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

            remoteMediaStream = null

            Logger.debug(TAG, "Closing peer connection factory.")
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null

            Logger.debug(TAG, "Closing peer connection done.")

//            PeerConnectionFactory.stopInternalTracingCapture()
//            PeerConnectionFactory.shutdownInternalTracer()

            try {
                localSurfaceViewRenderer?.release()
                remoteSurfaceViewRenderer?.release()
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
        Logger.error(TAG, "PeerConnection error: $errorMessage")
        executor.execute {
            listener?.onPeerConnectionError(errorMessage)
        }
    }

    private inner class InnerSdpObserver : SdpObserver {

        override fun onCreateSuccess(sessionDescription: SessionDescription?) {
            Logger.debug(TAG, "onCreateSuccess: $sessionDescription")

            if (sessionDescription == null) return

            if (localSessionDescription != null) {
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

            localSessionDescription = SessionDescription(sessionDescription.type, sdpDescription)

            executor.execute {
                Logger.debug(TAG, "Set local SDP from " + localSessionDescription?.type)
                peerConnection?.setLocalDescription(sdpObserver, localSessionDescription)
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
                        localSessionDescription?.let {
                            listener?.onLocalDescription(SessionDescriptionMapper.map(it))
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
                        localSessionDescription?.let {
                            listener?.onLocalDescription(SessionDescriptionMapper.map(it))
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
        fun onIceCandidate(iceCandidate: kz.q19.domain.model.webrtc.IceCandidate)
        fun onIceConnectionChange(iceConnectionState: IceConnectionState)
        fun onRenegotiationNeeded()
        fun onLocalDescription(sessionDescription: kz.q19.domain.model.webrtc.SessionDescription)

        fun onAddRemoteStream(mediaStream: MediaStream)
        fun onRemoveStream(mediaStream: MediaStream)

        fun onPeerConnectionError(errorMessage: String)
    }

}
