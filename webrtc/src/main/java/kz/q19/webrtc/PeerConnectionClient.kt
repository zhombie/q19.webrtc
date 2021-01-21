@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package kz.q19.webrtc

import android.app.Activity
import android.media.AudioManager
import kz.q19.domain.model.webrtc.IceConnectionState
import kz.q19.webrtc.audio.AppRTCAudioManager
import kz.q19.webrtc.core.ProxyVideoSink
import kz.q19.webrtc.core.SurfaceViewRenderer
import kz.q19.webrtc.core.Target
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
import kotlin.jvm.Throws

class PeerConnectionClient constructor(
    private val activity: Activity,
    private var localSurfaceViewRenderer: SurfaceViewRenderer? = null,
    private var remoteSurfaceViewRenderer: SurfaceViewRenderer? = null
) {

    companion object {
        private val TAG = PeerConnectionClient::class.java.simpleName
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var options: Options = Options()

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

    private var localVideoSinkTarget: Target? = null
    private var remoteVideoSinkTarget: Target? = null

    private var localSessionDescription: SessionDescription? = null

    private var localVideoSender: RtpSender? = null

    private var isInitiator = false

    var audioManager: AppRTCAudioManager? = null

    private var localVideoScalingType: ScalingType? = null
    private var remoteVideoScalingType: ScalingType? = null

    private var listener: Listener? = null

    @Throws(IllegalStateException::class)
    fun createPeerConnection(
        options: Options,
        listener: Listener? = null
    ): PeerConnection? {
        Logger.debug(TAG, "createPeerConnection() -> options: $options")

        this.options = options

        eglBase = EglBase.create()

        if (options.iceServers.any { it.url.isBlank() || it.urls.isBlank() }) {
            iceServers = emptyList()
        } else {
            iceServers = options.iceServers.map {
                val builder = when {
                    it.url.isNotBlank() ->
                        PeerConnection.IceServer.builder(it.url)
                    it.urls.isNotBlank() ->
                        PeerConnection.IceServer.builder(it.urls)
                    else ->
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

            val peerConnectionFactoryOptions = PeerConnectionFactory.Options()
            peerConnectionFactoryOptions.disableNetworkMonitor = true

            if (options.videoCodecHwAcceleration) {
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

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(peerConnectionFactoryOptions)
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

        if (options.isLocalVideoEnabled || options.isRemoteVideoEnabled) {
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

    fun initLocalCameraStream(isMirrored: Boolean = false, isZOrderMediaOverlay: Boolean = true) {
        Logger.debug(TAG, "initLocalCameraStream() -> isMirrored: $isMirrored")

        if (localSurfaceViewRenderer == null) {
            Logger.error(TAG, "Local SurfaceViewRenderer is null.")
            return
        }

        activity.runOnUiThread {
            localSurfaceViewRenderer?.init(eglBase?.eglBaseContext, null)
            localSurfaceViewRenderer?.setEnableHardwareScaler(true)
            localSurfaceViewRenderer?.setMirror(isMirrored)
            localSurfaceViewRenderer?.setZOrderMediaOverlay(isZOrderMediaOverlay)

            localVideoScalingType = ScalingType.SCALE_ASPECT_FILL
            localSurfaceViewRenderer?.setScalingType(localVideoScalingType)
        }
    }

    fun setRemoteSurfaceView(remoteSurfaceView: SurfaceViewRenderer?) {
        Logger.debug(TAG, "setRemoteSurfaceView() -> $remoteSurfaceView")

        this.remoteSurfaceViewRenderer = remoteSurfaceView
    }

    fun initRemoteCameraStream(isMirrored: Boolean = false, isZOrderMediaOverlay: Boolean = false) {
        Logger.debug(TAG, "initRemoteCameraStream() -> isMirrored: $isMirrored")

        if (remoteSurfaceViewRenderer == null) {
            Logger.error(TAG, "Remote SurfaceViewRenderer is null.")
            return
        }

        activity.runOnUiThread {
            remoteSurfaceViewRenderer?.init(eglBase?.eglBaseContext, null)
            remoteSurfaceViewRenderer?.setEnableHardwareScaler(true)
            remoteSurfaceViewRenderer?.setMirror(isMirrored)
            remoteSurfaceViewRenderer?.setZOrderMediaOverlay(isZOrderMediaOverlay)

            remoteVideoScalingType = ScalingType.SCALE_ASPECT_FILL
            remoteSurfaceViewRenderer?.setScalingType(remoteVideoScalingType)
        }
    }

    fun setLocalVideoScalingType(scalingType: kz.q19.webrtc.core.ScalingType) {
        activity.runOnUiThread {
            localVideoScalingType = ScalingTypeMapper.map(scalingType)
            localSurfaceViewRenderer?.setScalingType(localVideoScalingType)
        }
    }

    fun setRemoteVideoScalingType(scalingType: kz.q19.webrtc.core.ScalingType) {
        activity.runOnUiThread {
            remoteVideoScalingType = ScalingTypeMapper.map(scalingType)
            remoteSurfaceViewRenderer?.setScalingType(remoteVideoScalingType)
        }
    }

    fun addLocalStreamToPeer() {
        Logger.debug(TAG, "addLocalStreamToPeer()")

        localMediaStream = peerConnectionFactory?.createLocalMediaStream("ARDAMS")

        val audioTrack = createAudioTrack()
        if (audioTrack != null) {
            localMediaStream?.addTrack(audioTrack)
        }

        val videoTrack = createVideoTrack()
        if (videoTrack != null) {
            localMediaStream?.addTrack(videoTrack)
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
            remoteAudioTrack?.setEnabled(options.isRemoteAudioEnabled)
        }

        if (mediaStream.videoTracks.isNotEmpty()) {
            remoteVideoTrack = mediaStream.videoTracks.first()
            remoteVideoTrack?.setEnabled(options.isRemoteVideoEnabled)

            if (remoteSurfaceViewRenderer == null) {
                Logger.error(TAG, "Remote SurfaceViewRenderer is null.")
            } else {
                remoteVideoSink = ProxyVideoSink("RemoteVideoSink")
                remoteVideoSink?.setTarget(remoteSurfaceViewRenderer)
                remoteVideoTrack?.addSink(remoteVideoSink)
            }
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
            Logger.error(TAG, "Local SurfaceViewRenderer is null.")
            return null
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)

        localVideoSource = peerConnectionFactory?.createVideoSource(false)

        localVideoCapturer = createVideoCapturer()

        localVideoCapturer?.initialize(surfaceTextureHelper, activity, localVideoSource?.capturerObserver)

        localVideoCapturer?.startCapture(options.localVideoWidth, options.localVideoHeight, options.localVideoFPS)

        localVideoTrack = peerConnectionFactory?.createVideoTrack(options.localVideoTrackId, localVideoSource)
        localVideoTrack?.setEnabled(options.isLocalVideoEnabled)

        localVideoSink = ProxyVideoSink("LocalVideoSink")
        localVideoSink?.setTarget(localSurfaceViewRenderer)
        localVideoTrack?.addSink(localVideoSink)

        return localVideoTrack
    }

    private fun createAudioTrack(): AudioTrack? {
        Logger.debug(TAG, "createAudioTrack()")

        localAudioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())

        localAudioTrack = peerConnectionFactory?.createAudioTrack(options.localAudioTrackId, localAudioSource)
        localAudioTrack?.setEnabled(options.isLocalAudioEnabled)

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
                    if (maxBitrateKbps == null) null else maxBitrateKbps * options.bpsInKbps
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
                    listener?.onLocalIceCandidate(
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
            options.isLocalAudioEnabled = isEnabled
            localAudioTrack?.setEnabled(options.isLocalAudioEnabled)
        }
    }

    fun setRemoteAudioEnabled(isEnabled: Boolean) {
        executor.execute {
            options.isRemoteAudioEnabled = isEnabled
            remoteAudioTrack?.setEnabled(options.isRemoteAudioEnabled)
        }
    }

    fun setLocalVideoEnabled(isEnabled: Boolean) {
        executor.execute {
            options.isLocalVideoEnabled = isEnabled
            localVideoTrack?.setEnabled(options.isLocalVideoEnabled)
        }
    }

    fun setRemoteVideoEnabled(isEnabled: Boolean) {
        executor.execute {
            options.isRemoteVideoEnabled = isEnabled
            remoteVideoTrack?.setEnabled(options.isRemoteVideoEnabled)
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
        options.localVideoWidth = width
    }

    fun setLocalVideoResolutionHeight(height: Int) {
        options.localVideoHeight = height
    }

    fun isHDLocalVideo(): Boolean {
        return options.isLocalVideoEnabled && options.localVideoWidth * options.localVideoHeight >= 1280 * 720
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

    fun setVideoSinks(local: Target, remote: Target) {
        Logger.debug(TAG, "setVideoSinks() -> local: $local, remote: $remote")

        localVideoSinkTarget = setLocalVideoSink(local)
        remoteVideoSinkTarget = setRemoteVideoSink(remote)
    }

    fun setLocalVideoSink(target: Target): Target? {
        val set = when (target) {
            Target.LOCAL -> {
                localVideoSink?.setTarget(localSurfaceViewRenderer)
            }
            Target.REMOTE -> {
                localVideoSink?.setTarget(remoteSurfaceViewRenderer)
            }
        }
        return if (set == true) target else null
    }

    fun setRemoteVideoSink(target: Target): Target? {
        val set = when (target) {
            Target.LOCAL -> {
                remoteVideoSink?.setTarget(localSurfaceViewRenderer)
            }
            Target.REMOTE -> {
                remoteVideoSink?.setTarget(remoteSurfaceViewRenderer)
            }
        }
        return if (set == true) target else null
    }

    fun getLocalVideoSinkTarget(): Target? = localVideoSinkTarget

    fun getRemoteVideoSinkTarget(): Target? = remoteVideoSinkTarget

    fun pauseLocalVideoStream() {
        localSurfaceViewRenderer?.pauseVideo()
    }

    fun pauseRemoteVideoStream() {
        remoteSurfaceViewRenderer?.pauseVideo()
    }

    fun startLocalVideoCapture() {
        localVideoCapturer?.startCapture(options.localVideoWidth, options.localVideoHeight, options.localVideoFPS)
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

        options = Options()

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
                            listener?.onLocalSessionDescription(SessionDescriptionMapper.map(it))
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
                            listener?.onLocalSessionDescription(SessionDescriptionMapper.map(it))
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
        fun onLocalSessionDescription(sessionDescription: kz.q19.domain.model.webrtc.SessionDescription)
        fun onLocalIceCandidate(iceCandidate: kz.q19.domain.model.webrtc.IceCandidate)
        fun onIceConnectionChange(iceConnectionState: IceConnectionState)
        fun onRenegotiationNeeded()

        fun onAddRemoteStream(mediaStream: MediaStream)
        fun onRemoveStream(mediaStream: MediaStream)

        fun onPeerConnectionError(errorMessage: String)
    }

}
