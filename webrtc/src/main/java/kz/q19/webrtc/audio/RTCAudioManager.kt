@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package kz.q19.webrtc.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import kz.q19.webrtc.utils.AssertUtils.assertIsTrue
import kz.q19.webrtc.utils.Logger
import kz.q19.webrtc.utils.Logger.logDeviceInfo
import kz.q19.webrtc.utils.ThreadUtils.threadInfo
import org.webrtc.ThreadUtils
import java.util.*

/**
 * [RTCAudioManager] manages all audio related parts.
 */
class RTCAudioManager private constructor(private val context: Context) {

    companion object {
        private val TAG = RTCAudioManager::class.java.simpleName

        /** Construction.  */
        fun create(context: Context): RTCAudioManager {
            return RTCAudioManager(context)
        }
    }

    object SpeakerPhone {
        const val AUTO = "auto"
        const val TRUE = "true"
        const val FALSE = "false"
    }

    /**
     * AudioDevice is the names of possible audio devices that we currently
     * support.
     */
    enum class AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE
    }

    /**
     * AudioManager state.
     */
    enum class AudioManagerState {
        UNINITIALIZED, PREINITIALIZED, RUNNING
    }

    /**
     * Selected audio device change event.
     */
    fun interface AudioManagerEvents {
        // Callback fired once audio device is changed or list of available audio devices changed.
        fun onAudioDeviceChanged(
            selectedAudioDevice: AudioDevice,
            availableAudioDevices: Set<AudioDevice>
        )
    }

    private val audioManager: AudioManager?
    private var audioManagerEvents: AudioManagerEvents? = null
    private var audioManagerState: AudioManagerState
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var hasWiredHeadset = false

    // Default audio device; speaker phone for video calls or earpiece for audio
    // only calls.
    private var defaultAudioDevice: AudioDevice

    // Contains the currently selected audio device.
    // This device is changed automatically using a certain scheme where e.g.
    // a wired headset "wins" over speaker phone. It is also possible for a
    // user to explicitly select a device (and override any predefined scheme).
    // See |userSelectedAudioDevice| for details.
    private var selectedAudioDevice: AudioDevice = AudioDevice.NONE

    // Contains the user-selected audio device which overrides the predefined
    // selection scheme.
    private var userSelectedAudioDevice: AudioDevice = AudioDevice.NONE

    // Contains speakerphone setting: auto, true or false
    private val useSpeakerphone: String

    // Proximity sensor object. It measures the proximity of an object in cm
    // relative to the view screen of a device and can therefore be used to
    // assist device switching (close to ear <=> use headset earpiece if
    // available, far from ear <=> use speaker phone).
    private var proximitySensor: RTCProximitySensor?

    // Handles all tasks related to Bluetooth headset devices.
    private val bluetoothManager: RTCBluetoothManager

    // Contains a list of available audio devices. A Set collection is used to
    // avoid duplicate elements.
    private var audioDevices: MutableSet<AudioDevice> = HashSet()

    // Broadcast receiver for wired headset intent broadcasts.
    private val wiredHeadsetReceiver: BroadcastReceiver

    private var audioFocusRequest: AudioFocusRequest? = null

    // Callback method for changes in audio focus.
    private var audioFocusChangeListener: OnAudioFocusChangeListener? = null

    init {
        Logger.debug(TAG, "created")
        ThreadUtils.checkIsOnMainThread()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager?
        bluetoothManager = RTCBluetoothManager.create(context, this)
        wiredHeadsetReceiver = WiredHeadsetReceiver()
        audioManagerState = AudioManagerState.UNINITIALIZED

        useSpeakerphone = SpeakerPhone.AUTO
        Logger.debug(TAG, "useSpeakerphone: $useSpeakerphone")
        defaultAudioDevice = if (useSpeakerphone == SpeakerPhone.FALSE) {
            AudioDevice.EARPIECE
        } else {
            AudioDevice.SPEAKER_PHONE
        }

        // Create and initialize the proximity sensor.
        // Tablet devices (e.g. Nexus 7) does not support proximity sensors.
        // Note that, the sensor will not be active until start() has been called.
        proximitySensor = RTCProximitySensor.create(context) { onProximitySensorChangedState() }
        Logger.debug(TAG, "defaultAudioDevice: $defaultAudioDevice")
        logDeviceInfo(TAG)
    }

    /**
     * This method is called when the proximity sensor reports a state change,
     * e.g. from "NEAR to FAR" or from "FAR to NEAR".
     */
    private fun onProximitySensorChangedState() {
        if (useSpeakerphone != SpeakerPhone.AUTO) {
            return
        }

        // The proximity sensor should only be activated when there are exactly two
        // available audio devices.
        if (audioDevices.size == 2 &&
            audioDevices.contains(AudioDevice.EARPIECE) &&
            audioDevices.contains(AudioDevice.SPEAKER_PHONE)
        ) {
            if (proximitySensor?.sensorReportsNearState() == true) {
                // Sensor reports that a "handset is being held up to a person's ear",
                // or "something is covering the light sensor".
                setAudioDeviceInternal(AudioDevice.EARPIECE)
            } else {
                // Sensor reports that a "handset is removed from a person's ear", or
                // "the light sensor is no longer covered".
                setAudioDeviceInternal(AudioDevice.SPEAKER_PHONE)
            }
        }
    }

    /* Receiver which handles changes in wired headset availability. */
    private inner class WiredHeadsetReceiver : BroadcastReceiver() {
        private val STATE_UNPLUGGED = 0
        private val STATE_PLUGGED = 1

        private val HAS_NO_MIC = 0
        private val HAS_MIC = 1

        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra("state", STATE_UNPLUGGED)
            val microphone = intent.getIntExtra("microphone", HAS_NO_MIC)
            val name = intent.getStringExtra("name")
            Logger.debug(
                TAG, "WiredHeadsetReceiver.onReceive $threadInfo: a=${intent.action}, " +
                        "s=" + (if (state == STATE_UNPLUGGED) "unplugged" else "plugged") + ", " +
                        "m=" + (if (microphone == HAS_MIC) "mic" else "no mic") + ", " +
                        "n=" + name + ", " +
                        "sb=" + isInitialStickyBroadcast
            )
            hasWiredHeadset = state == STATE_PLUGGED
            updateAudioDeviceState()
        }
    }

    fun start(audioManagerEvents: AudioManagerEvents) {
        Logger.debug(TAG, "start")
        ThreadUtils.checkIsOnMainThread()
        if (audioManagerState == AudioManagerState.RUNNING) {
            Logger.error(TAG, "AudioManager is already active")
            return
        }
        // TODO(henrika): perhaps call new method called preInitAudio() here if UNINITIALIZED.

        Logger.debug(TAG, "AudioManager starts...")
        this.audioManagerEvents = audioManagerEvents
        audioManagerState = AudioManagerState.RUNNING

        // Store current audio state so we can restore it when stop() is called.
        if (audioManager != null) {
            savedAudioMode = audioManager.mode
            savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn
            savedIsMicrophoneMute = audioManager.isMicrophoneMute
        }
        hasWiredHeadset = hasWiredHeadset()

        // Create an AudioManager.OnAudioFocusChangeListener instance.
        // Called on the listener to notify if the audio focus for this listener has been changed.
        // The |focusChange| value indicates whether the focus was gained, whether the focus was lost,
        // and whether that loss is transient, or whether the new focus holder will hold it for an
        // unknown amount of time.
        // TODO(henrika): possibly extend support of handling audio-focus changes. Only contains
        //  logging for now.
        audioFocusChangeListener = OnAudioFocusChangeListener { focusChange: Int ->
            val typeOfChange: String = when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "AUDIOFOCUS_GAIN_TRANSIENT"
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE"
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"
                AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
                else -> "AUDIOFOCUS_INVALID"
            }
            Logger.debug(TAG, "onAudioFocusChange: $typeOfChange")
        }

        // Request audio playout focus (without ducking) and install listener for changes in focus.
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioFocusRequestBuilder = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            audioFocusChangeListener?.let {
                audioFocusRequestBuilder.setOnAudioFocusChangeListener(it)
            }
            val audioAttributesBuilder = AudioAttributes.Builder()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioAttributesBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                audioAttributesBuilder.setHapticChannelsMuted(true)
            }
            audioAttributesBuilder.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            audioAttributesBuilder.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            val audioAttributes = audioAttributesBuilder.build()
            audioFocusRequestBuilder.setAudioAttributes(audioAttributes)
            val audioFocusRequest = audioFocusRequestBuilder.build()
            this.audioFocusRequest = audioFocusRequest
            audioManager?.requestAudioFocus(audioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Logger.debug(TAG, "Audio focus request granted for VOICE_CALL streams")
        } else {
            Logger.error(TAG, "Audio focus request failed")
        }

        // Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
        // required to be in this mode when playout and/or recording starts for
        // best possible VoIP performance.
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION

        // Always disable microphone mute during a WebRTC call.
        setMicrophoneMute(false)

        // Set initial device states.
        userSelectedAudioDevice = AudioDevice.NONE
        selectedAudioDevice = AudioDevice.NONE
        audioDevices.clear()

        // Initialize and start Bluetooth if a BT device is available or initiate
        // detection of new (enabled) BT devices.
        bluetoothManager.start()

        // Do initial selection of audio device. This setting can later be changed
        // either by adding/removing a BT or wired headset or by covering/uncovering
        // the proximity sensor.
        updateAudioDeviceState()

        // Register receiver for broadcast intents related to adding/removing a
        // wired headset.
        registerReceiver(wiredHeadsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        Logger.debug(TAG, "AudioManager started")
    }

    fun stop() {
        Logger.debug(TAG, "stop")
        ThreadUtils.checkIsOnMainThread()
        if (audioManagerState != AudioManagerState.RUNNING) {
            Logger.error(TAG, "Trying to stop AudioManager in incorrect state: $audioManagerState")
            return
        }
        audioManagerState = AudioManagerState.UNINITIALIZED

        unregisterReceiver(wiredHeadsetReceiver)

        bluetoothManager.stop()

        // Restore previously stored audio states.
        setSpeakerphoneOn(savedIsSpeakerPhoneOn)
        setMicrophoneMute(savedIsMicrophoneMute)

        if (audioManager != null) {
            audioManager.mode = savedAudioMode
            // Abandon audio focus. Gives the previous focus owner, if any, focus.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
            audioFocusChangeListener = null
            Logger.debug(TAG, "Abandoned audio focus for VOICE_CALL streams")
        }

        proximitySensor?.stop()
        proximitySensor = null

        audioManagerEvents = null
        Logger.debug(TAG, "AudioManager stopped")
    }

    /**
     * Changes selection of the currently active audio device.
     */
    private fun setAudioDeviceInternal(device: AudioDevice) {
        Logger.debug(TAG, "setAudioDeviceInternal(device=$device)")
        assertIsTrue(audioDevices.contains(device))

        when (device) {
            AudioDevice.SPEAKER_PHONE ->
                setSpeakerphoneOn(true)
            AudioDevice.EARPIECE, AudioDevice.WIRED_HEADSET, AudioDevice.BLUETOOTH ->
                setSpeakerphoneOn(false)
            else ->
                Logger.error(TAG, "Invalid audio device selection")
        }
        selectedAudioDevice = device
    }

    /**
     * Changes default audio device.
     * TODO(henrika): add usage of this method in the AppRTCMobile client.
     */
    fun setDefaultAudioDevice(defaultDevice: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        when (defaultDevice) {
            AudioDevice.SPEAKER_PHONE -> {
                defaultAudioDevice = defaultDevice
            }
            AudioDevice.EARPIECE -> {
                defaultAudioDevice = if (hasEarpiece()) {
                    defaultDevice
                } else {
                    AudioDevice.SPEAKER_PHONE
                }
            }
            else ->
                Logger.error(TAG, "Invalid default audio device selection")
        }
        Logger.debug(TAG, "setDefaultAudioDevice(device=$defaultAudioDevice)")
        updateAudioDeviceState()
    }

    /**
     * Changes selection of the currently active audio device.
     */
    fun selectAudioDevice(device: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        if (!audioDevices.contains(device)) {
            Logger.error(TAG, "Can not select $device from available $audioDevices")
        }
        userSelectedAudioDevice = device
        updateAudioDeviceState()
    }

    /**
     * Returns current set of available/selectable audio devices.
     */
    fun getAudioDevices(): Set<AudioDevice> {
        ThreadUtils.checkIsOnMainThread()
        return Collections.unmodifiableSet(HashSet(audioDevices))
    }

    /**
     * Returns the currently selected audio device.
     */
    fun getSelectedAudioDevice(): AudioDevice {
        ThreadUtils.checkIsOnMainThread()
        return selectedAudioDevice
    }

    /**
     * Helper method for receiver registration.
     */
    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter): Intent? {
        return context.registerReceiver(receiver, filter)
    }

    /**
     * Helper method for unregistration of an existing receiver.
     */
    private fun unregisterReceiver(receiver: BroadcastReceiver) {
        context.unregisterReceiver(receiver)
    }

    /**
     * Sets the speaker phone mode.
     */
    fun setSpeakerphoneOn(on: Boolean): Boolean {
        if (audioManager == null) return false
        val wasOn = audioManager.isSpeakerphoneOn
        if (wasOn == on) {
            return false
        }
        audioManager.isSpeakerphoneOn = on
        return audioManager.isSpeakerphoneOn == on
    }

    /**
     * Sets the microphone mute state.
     */
    fun setMicrophoneMute(on: Boolean): Boolean {
        if (audioManager == null) return false
        val wasMuted = audioManager.isMicrophoneMute
        if (wasMuted == on) {
            return false
        }
        audioManager.isMicrophoneMute = on
        return audioManager.isMicrophoneMute == on
    }

    /**
     * Gets the current earpiece state.
     */
    private fun hasEarpiece(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    /**
     * Checks whether a wired headset is connected or not.
     * This is not a valid indication that audio playback is actually over
     * the wired headset as audio routing depends on other conditions. We
     * only use it as an early indicator (during initialization) of an attached
     * wired headset.
     */
    private fun hasWiredHeadset(): Boolean {
        return if (audioManager == null) {
            false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS or AudioManager.GET_DEVICES_INPUTS)
                for (device in devices) {
                    val type = device.type
                    if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                        Logger.debug(TAG, "hasWiredHeadset: found wired headset")
                        return true
                    } else if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                        Logger.debug(TAG, "hasWiredHeadset: found USB audio device")
                        return true
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isWiredHeadsetOn
            }
            false
        }
    }

    /**
     * Updates list of possible audio devices and make new device selection.
     */
    fun updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread()
        Logger.debug(TAG, "--- updateAudioDeviceState: wired headset=$hasWiredHeadset, BT state=${bluetoothManager.state}")
        Logger.debug(TAG, "Device status: available=$audioDevices, selected=$selectedAudioDevice, user selected=$userSelectedAudioDevice")

        // Check if any Bluetooth headset is connected. The internal BT state will
        // change accordingly.
        // TODO(henrika): perhaps wrap required state into BT manager.
        if (bluetoothManager.state == RTCBluetoothManager.State.HEADSET_AVAILABLE || bluetoothManager.state == RTCBluetoothManager.State.HEADSET_UNAVAILABLE || bluetoothManager.state == RTCBluetoothManager.State.SCO_DISCONNECTING) {
            bluetoothManager.updateDevice()
        }

        // Update the set of available audio devices.
        val newAudioDevices: MutableSet<AudioDevice> = HashSet()
        if (bluetoothManager.state == RTCBluetoothManager.State.SCO_CONNECTED || bluetoothManager.state == RTCBluetoothManager.State.SCO_CONNECTING || bluetoothManager.state == RTCBluetoothManager.State.HEADSET_AVAILABLE) {
            newAudioDevices.add(AudioDevice.BLUETOOTH)
        }
        if (hasWiredHeadset) {
            // If a wired headset is connected, then it is the only possible option.
            newAudioDevices.add(AudioDevice.WIRED_HEADSET)
        } else {
            // No wired headset, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            newAudioDevices.add(AudioDevice.SPEAKER_PHONE)
            if (hasEarpiece()) {
                newAudioDevices.add(AudioDevice.EARPIECE)
            }
        }
        // Store state which is set to true if the device list has changed.
        var audioDeviceSetUpdated = audioDevices != newAudioDevices
        // Update the existing audio device set.
        audioDevices = newAudioDevices
        // Correct user selected audio devices if needed.
        if (bluetoothManager.state == RTCBluetoothManager.State.HEADSET_UNAVAILABLE && userSelectedAudioDevice == AudioDevice.BLUETOOTH) {
            // If BT is not available, it can't be the user selection.
            userSelectedAudioDevice = AudioDevice.NONE
        }
        if (hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
            // If user selected speaker phone, but then plugged wired headset then make
            // wired headset as user selected device.
            userSelectedAudioDevice = AudioDevice.WIRED_HEADSET
        }
        if (!hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {
            // If user selected wired headset, but then unplugged wired headset then make
            // speaker phone as user selected device.
            userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE
        }

        // Need to start Bluetooth if it is available and user either selected it explicitly or
        // user did not select any output device.
        val needBluetoothAudioStart =
            (bluetoothManager.state == RTCBluetoothManager.State.HEADSET_AVAILABLE
                    && (userSelectedAudioDevice == AudioDevice.NONE
                    || userSelectedAudioDevice == AudioDevice.BLUETOOTH))

        // Need to stop Bluetooth audio if user selected different device and
        // Bluetooth SCO connection is established or in the process.
        val needBluetoothAudioStop =
            ((bluetoothManager.state == RTCBluetoothManager.State.SCO_CONNECTED
                    || bluetoothManager.state == RTCBluetoothManager.State.SCO_CONNECTING)
                    && (userSelectedAudioDevice != AudioDevice.NONE
                    && userSelectedAudioDevice != AudioDevice.BLUETOOTH))
        if (bluetoothManager.state == RTCBluetoothManager.State.HEADSET_AVAILABLE || bluetoothManager.state == RTCBluetoothManager.State.SCO_CONNECTING || bluetoothManager.state == RTCBluetoothManager.State.SCO_CONNECTED) {
            Logger.debug(TAG, "Need BT audio: start=$needBluetoothAudioStart, stop=$needBluetoothAudioStop, BT state=${bluetoothManager.state}")
        }

        // Start or stop Bluetooth SCO connection given states set earlier.
        if (needBluetoothAudioStop) {
            bluetoothManager.stopScoAudio()
            bluetoothManager.updateDevice()
        }
        if (needBluetoothAudioStart && !needBluetoothAudioStop) {
            // Attempt to start Bluetooth SCO audio (takes a few second to start).
            if (!bluetoothManager.startScoAudio()) {
                // Remove BLUETOOTH from list of available devices since SCO failed.
                audioDevices.remove(AudioDevice.BLUETOOTH)
                audioDeviceSetUpdated = true
            }
        }

        // Update selected audio device.
        val newAudioDevice: AudioDevice = when {
            bluetoothManager.state == RTCBluetoothManager.State.SCO_CONNECTED -> {
                // If a Bluetooth is connected, then it should be used as output audio
                // device. Note that it is not sufficient that a headset is available;
                // an active SCO channel must also be up and running.
                AudioDevice.BLUETOOTH
            }
            hasWiredHeadset -> {
                // If a wired headset is connected, but Bluetooth is not, then wired headset is used as
                // audio device.
                AudioDevice.WIRED_HEADSET
            }
            else -> {
                // No wired headset and no Bluetooth, hence the audio-device list can contain speaker
                // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
                // |defaultAudioDevice| contains either AudioDevice.SPEAKER_PHONE or AudioDevice.EARPIECE
                // depending on the user's selection.
                defaultAudioDevice
            }
        }
        // Switch to new device but only if there has been any changes.
        if (newAudioDevice != selectedAudioDevice || audioDeviceSetUpdated) {
            // Do the required device switch.
            setAudioDeviceInternal(newAudioDevice)
            Logger.debug(TAG, "New device status: available=$audioDevices, selected=$newAudioDevice")
            // Notify a listening client that audio device has been changed.
            audioManagerEvents?.onAudioDeviceChanged(selectedAudioDevice, audioDevices)
        }
        Logger.debug(TAG, "--- updateAudioDeviceState done")
    }

}