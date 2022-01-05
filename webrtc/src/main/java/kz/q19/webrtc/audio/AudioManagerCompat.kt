package kz.q19.webrtc.audio

import android.content.Context
import android.content.Intent
import android.media.*
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import androidx.annotation.RequiresApi
import kz.q19.webrtc.utils.Logger
import java.lang.ref.WeakReference

internal abstract class AudioManagerCompat private constructor(
    private val contextReference: WeakReference<Context>
) {

    companion object {
        private val TAG: String = AudioManagerCompat::class.java.simpleName

        private const val DEFAULT_AUDIOFOCUS_GAIN = AudioManager.AUDIOFOCUS_GAIN

        fun create(context: Context): AudioManagerCompat =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    Api26AudioManagerCompat(context)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ->
                    Api21AudioManagerCompat(context)
                else ->
                    Api19AudioManagerCompat(context)
            }
    }

    constructor(context: Context) : this(WeakReference(context))

    private val context: Context?
        get() = contextReference.get()

    protected val audioManager: AudioManager? =
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager?

    // Callback method for changes in audio focus.
    //
    // Create an AudioManager.OnAudioFocusChangeListener instance.
    // Called on the listener to notify if the audio focus for this listener has been changed.
    // The |focusChange| value indicates whether the focus was gained, whether the focus was lost,
    // and whether that loss is transient, or whether the new focus holder will hold it for an
    // unknown amount of time.
    // TODO: Possibly extend support of handling audio-focus changes. Only contains logging for now.
    protected val onAudioFocusChangeListener = OnAudioFocusChangeListener { focusChange: Int ->
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

    var savedAudioMode: Int = AudioManager.MODE_NORMAL
    var savedIsSpeakerPhoneOn: Boolean = false
    var savedIsMicrophoneMute: Boolean = false
    var savedWiredHeadset: Boolean = false

    abstract fun getWiredHeadsetPlugBroadcastAction(): String

    /**
     * Checks whether a wired headset is connected or not.
     * This is not a valid indication that audio playback is actually over
     * the wired headset as audio routing depends on other conditions. We
     * only use it as an early indicator (during initialization) of an attached
     * wired headset.
     */
    abstract fun isWiredHeadsetOn(): Boolean

    abstract fun createSoundPool(): SoundPool?

    abstract fun requestCallAudioFocus()
    abstract fun abandonCallAudioFocus()

    // Store current audio state so we can restore it when stop() is called.
    fun storeState() {
        audioManager?.let {
            savedAudioMode = audioManager.mode
            savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn
            savedIsMicrophoneMute = audioManager.isMicrophoneMute
            savedWiredHeadset = isWiredHeadsetOn()
        }
    }

    // Restore previously stored audio states.
    fun restoreState() {
        setSpeakerphoneOn(savedIsSpeakerPhoneOn)
        setMicrophoneMute(savedIsMicrophoneMute)
        audioManager?.mode = savedAudioMode
    }

    fun setMode(mode: Int) {
        audioManager?.mode = mode
    }

    fun setSpeakerphoneOn(on: Boolean): Boolean {
        if (audioManager == null) return false
        val isOn = audioManager.isSpeakerphoneOn
        if (isOn == on) return false
        audioManager.isSpeakerphoneOn = on
        return audioManager.isSpeakerphoneOn == on
    }

    fun setMicrophoneMute(on: Boolean): Boolean {
        if (audioManager == null) return false
        val isMute = audioManager.isMicrophoneMute
        if (isMute == on) return false
        audioManager.isMicrophoneMute = on
        return audioManager.isMicrophoneMute == on
    }

    @RequiresApi(26)
    private class Api26AudioManagerCompat(
        context: Context
    ) : AudioManagerCompat(context) {

        companion object {
            private val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .also {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        it.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                        it.setHapticChannelsMuted(true)
                    }
                }
                .build()
        }

        private var audioFocusRequest: AudioFocusRequest? = null

        override fun getWiredHeadsetPlugBroadcastAction(): String {
            return AudioManager.ACTION_HEADSET_PLUG
        }

        override fun isWiredHeadsetOn(): Boolean {
            if (audioManager == null) return false
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
            return false
        }

        override fun createSoundPool(): SoundPool {
            return SoundPool.Builder()
                .setAudioAttributes(AUDIO_ATTRIBUTES)
                .setMaxStreams(1)
                .build()
        }

        override fun requestCallAudioFocus() {
            if (audioFocusRequest != null) {
                Logger.warn(TAG, "Already requested audio focus. Ignoring...")
                return
            }
            audioFocusRequest = AudioFocusRequest.Builder(DEFAULT_AUDIOFOCUS_GAIN)
                .setAudioAttributes(AUDIO_ATTRIBUTES)
                .setOnAudioFocusChangeListener(onAudioFocusChangeListener)
                .build()
            val result = audioFocusRequest?.let { audioManager?.requestAudioFocus(it) }
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Logger.warn(TAG, "Audio focus not granted. Result code: $result")
            }
        }

        override fun abandonCallAudioFocus() {
            if (audioFocusRequest == null) {
                Logger.warn(TAG, "Don't currently have audio focus. Ignoring...")
                return
            }
            val result = audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Logger.warn(TAG, "Audio focus abandon failed. Result code: $result")
            }
            audioFocusRequest = null
        }

    }

    @RequiresApi(21)
    private class Api21AudioManagerCompat(
        context: Context
    ) : Api19AudioManagerCompat(context) {

        companion object {
            private val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                .build()
        }

        override fun getWiredHeadsetPlugBroadcastAction(): String {
            return AudioManager.ACTION_HEADSET_PLUG
        }

        override fun createSoundPool(): SoundPool {
            return SoundPool.Builder()
                .setAudioAttributes(AUDIO_ATTRIBUTES)
                .setMaxStreams(1)
                .build()
        }

    }

    @Suppress("DEPRECATION")
    private open class Api19AudioManagerCompat constructor(
        context: Context
    ) : AudioManagerCompat(context) {

        override fun getWiredHeadsetPlugBroadcastAction(): String {
            return Intent.ACTION_HEADSET_PLUG
        }

        override fun isWiredHeadsetOn(): Boolean {
            return audioManager?.isWiredHeadsetOn == true
        }

        override fun createSoundPool(): SoundPool {
            return SoundPool(1, AudioManager.STREAM_VOICE_CALL, 0)
        }

        override fun requestCallAudioFocus() {
            val result = audioManager?.requestAudioFocus(
                onAudioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                DEFAULT_AUDIOFOCUS_GAIN
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Logger.warn(TAG, "Audio focus not granted. Result code: $result")
            }
        }

        override fun abandonCallAudioFocus() {
            val result = audioManager?.abandonAudioFocus(onAudioFocusChangeListener)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Logger.warn(TAG, "Audio focus abandon failed. Result code: $result")
            }
        }
    }

}