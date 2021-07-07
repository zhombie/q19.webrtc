@file:Suppress("MissingPermission")

package kz.q19.webrtc.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.ServiceListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kz.q19.utils.android.audioManager
import kz.q19.webrtc.utils.Logger
import kz.q19.webrtc.utils.ThreadUtils.threadInfo
import org.webrtc.ThreadUtils

/**
 * [RTCProximitySensor] manages functions related to Bluetooth devices.
 */
internal open class RTCBluetoothManager protected constructor(
    private val context: Context,
    private val rtcAudioManager: RTCAudioManager
) {

    companion object {
        private val TAG = RTCBluetoothManager::class.java.simpleName

        // Timeout interval for starting or stopping audio to a Bluetooth SCO device.
        private const val BLUETOOTH_SCO_TIMEOUT_MS = 4000

        // Maximum number of SCO connection attempts.
        private const val MAX_SCO_CONNECTION_ATTEMPTS = 2

        /**
         * Construction.
         */
        fun create(context: Context, audioManager: RTCAudioManager): RTCBluetoothManager {
            Log.d(TAG, "create: $threadInfo")
            return RTCBluetoothManager(context, audioManager)
        }
    }

    private val audioManager: AudioManager?
    private val handler: Handler
    var scoConnectionAttempts: Int = 0
    private var bluetoothState: State
    private val bluetoothServiceListener: ServiceListener
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private val bluetoothHeadsetReceiver: BroadcastReceiver

    init {
        Log.d(TAG, "created")
        ThreadUtils.checkIsOnMainThread()
        audioManager = context.audioManager
        bluetoothState = State.UNINITIALIZED
        bluetoothServiceListener = BluetoothServiceListener()
        bluetoothHeadsetReceiver = BluetoothHeadsetBroadcastReceiver()
        handler = Handler(Looper.getMainLooper())
    }

    // Bluetooth connection state.
    enum class State {
        // Bluetooth is not available; no adapter or Bluetooth is off.
        UNINITIALIZED,

        // Bluetooth error happened when trying to start Bluetooth.
        ERROR,

        // Bluetooth proxy object for the Headset profile exists, but no connected headset devices,
        // SCO is not started or disconnected.
        HEADSET_UNAVAILABLE,

        // Bluetooth proxy object for the Headset profile connected, connected Bluetooth headset
        // present, but SCO is not started or disconnected.
        HEADSET_AVAILABLE,

        // Bluetooth audio SCO connection with remote device is closing.
        SCO_DISCONNECTING,

        // Bluetooth audio SCO connection with remote device is initiated.
        SCO_CONNECTING,

        // Bluetooth audio SCO connection with remote device is established.
        SCO_CONNECTED
    }

    // Runs when the Bluetooth timeout expires. We use that timeout after calling
    // startScoAudio() or stopScoAudio() because we're not guaranteed to get a
    // callback after those calls.
    private val bluetoothTimeoutRunnable = Runnable { bluetoothTimeout() }

    /**
     * Implementation of an interface that notifies BluetoothProfile IPC clients when they have been
     * connected to or disconnected from the service.
     */
    private inner class BluetoothServiceListener : ServiceListener {
        // Called to notify the client when the proxy object has been connected to the service.
        // Once we have the profile proxy object, we can use it to monitor the state of the
        // connection and perform other operations that are relevant to the headset profile.
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HEADSET) return
            if (bluetoothState == State.UNINITIALIZED) return
            Logger.debug(TAG, "BluetoothServiceListener.onServiceConnected: " +
                    "BT state=$bluetoothState")
            // Android only supports one connected Bluetooth Headset at a time.
            if (proxy is BluetoothHeadset) {
                bluetoothHeadset = proxy
            }
            updateAudioDeviceState()
            Logger.debug(TAG, "onServiceConnected done: BT state=$bluetoothState")
        }

        /**
         * Notifies the client when the proxy object has been disconnected from the service.
         */
        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HEADSET) return
            // if (bluetoothState == State.UNINITIALIZED) return
            Logger.debug(TAG, "BluetoothServiceListener.onServiceDisconnected: " +
                    "BT state=$bluetoothState")
            stopScoAudio()
            bluetoothHeadset = null
            bluetoothDevice = null
            bluetoothState = State.HEADSET_UNAVAILABLE
            updateAudioDeviceState()
            Logger.debug(TAG, "onServiceDisconnected done: BT state=$bluetoothState")
        }
    }

    // Intent broadcast receiver which handles changes in Bluetooth device availability.
    // Detects headset changes and Bluetooth SCO state changes.
    private inner class BluetoothHeadsetBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (bluetoothState == State.UNINITIALIZED) return

            val action = intent.action
            // Change in connection state of the Headset profile. Note that the
            // change does not tell us anything about whether we're streaming
            // audio to BT over SCO. Typically received when user turns on a BT
            // headset while audio is active using another audio device.
            if (action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothHeadset.EXTRA_STATE,
                    BluetoothHeadset.STATE_DISCONNECTED
                )
                Logger.debug(TAG, "BluetoothHeadsetBroadcastReceiver.onReceive: " +
                        "a=$action, " +
                        "s=${stateToString(state)}, " +
                        "sb=$isInitialStickyBroadcast, " +
                        "BT state: $bluetoothState"
                )
                when (state) {
                    BluetoothHeadset.STATE_CONNECTED -> {
                        scoConnectionAttempts = 0
                        updateAudioDeviceState()
                    }
                    BluetoothHeadset.STATE_CONNECTING -> {
                        // No action needed.
                    }
                    BluetoothHeadset.STATE_DISCONNECTING -> {
                        // No action needed.
                    }
                    BluetoothHeadset.STATE_DISCONNECTED -> {
                        // Bluetooth is probably powered off during the call.
                        stopScoAudio()
                        updateAudioDeviceState()
                    }
                }
                // Change in the audio (SCO) connection state of the Headset profile.
                // Typically received after call to startScoAudio() has finalized.
            } else if (action == BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothHeadset.EXTRA_STATE,
                    BluetoothHeadset.STATE_AUDIO_DISCONNECTED
                )
                Logger.debug(TAG, "BluetoothHeadsetBroadcastReceiver.onReceive: " +
                        "a=ACTION_AUDIO_STATE_CHANGED, " +
                        "s=${stateToString(state)}, " +
                        "sb=$isInitialStickyBroadcast, " +
                        "BT state: $bluetoothState"
                )
                if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                    cancelTimer()
                    if (bluetoothState == State.SCO_CONNECTING) {
                        Logger.debug(TAG, "+++ Bluetooth audio SCO is now connected")
                        bluetoothState = State.SCO_CONNECTED
                        scoConnectionAttempts = 0
                        updateAudioDeviceState()
                    } else {
                        Log.w(TAG, "Unexpected state BluetoothHeadset.STATE_AUDIO_CONNECTED")
                    }
                } else if (state == BluetoothHeadset.STATE_AUDIO_CONNECTING) {
                    Logger.debug(TAG, "+++ Bluetooth audio SCO is now connecting...")
                } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    Logger.debug(TAG, "+++ Bluetooth audio SCO is now disconnected")
                    if (isInitialStickyBroadcast) {
                        Logger.debug(TAG, "Ignore $state initial sticky broadcast.")
                        return
                    }
                    updateAudioDeviceState()
                }
            }
            Logger.debug(TAG, "onReceive done: BT state=$bluetoothState")
        }
    }

    /**
     * Returns the internal state.
     */
    val state: State
        get() {
            ThreadUtils.checkIsOnMainThread()
            return bluetoothState
        }

    /**
     * Activates components required to detect Bluetooth devices and to enable
     * BT SCO (audio is routed via BT SCO) for the headset profile. The end
     * state will be HEADSET_UNAVAILABLE but a state machine has started which
     * will start a state change sequence where the final outcome depends on
     * if/when the BT headset is enabled.
     * Example of state change sequence when start() is called while BT device
     * is connected and enabled:
     * UNINITIALIZED --> HEADSET_UNAVAILABLE --> HEADSET_AVAILABLE -->
     * SCO_CONNECTING --> SCO_CONNECTED <==> audio is now routed via BT SCO.
     * Note that the [RTCAudioManager] is also involved in driving this state
     * change.
     */
    fun start() {
        ThreadUtils.checkIsOnMainThread()
        Logger.debug(TAG, "start")
        if (!hasPermission(context, Manifest.permission.BLUETOOTH)) {
            Log.w(TAG, "Process (pid=" + Process.myPid() + ") lacks BLUETOOTH permission")
            return
        }
        if (bluetoothState != State.UNINITIALIZED) {
            Log.w(TAG, "Invalid BT state")
            return
        }
        bluetoothHeadset = null
        bluetoothDevice = null
        scoConnectionAttempts = 0
        // Get a handle to the default local Bluetooth adapter.
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Device does not support Bluetooth")
            return
        }
        // Ensure that the device supports use of BT SCO audio for off call use cases.
        if (audioManager != null && !audioManager.isBluetoothScoAvailableOffCall) {
            Logger.error(TAG, "Bluetooth SCO audio is not available off call")
            return
        }
        bluetoothAdapter?.let {
            logBluetoothAdapterInfo(it)
        }
        // Establish a connection to the HEADSET profile (includes both Bluetooth Headset and
        // Hands-Free) proxy object and install a listener.
        if (!getBluetoothProfileProxy(context, bluetoothServiceListener, BluetoothProfile.HEADSET)) {
            Logger.error(TAG, "BluetoothAdapter.getProfileProxy(HEADSET) failed")
            return
        }
        // Register receivers for BluetoothHeadset change notifications.
        val bluetoothHeadsetFilter = IntentFilter()
        // Register receiver for change in connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        // Register receiver for change in audio connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        registerReceiver(bluetoothHeadsetReceiver, bluetoothHeadsetFilter)
        Logger.debug(TAG, "HEADSET " +
                "profile state: ${stateToString(bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET))}")
        Logger.debug(TAG, "Bluetooth proxy for headset profile has started")
        bluetoothState = State.HEADSET_UNAVAILABLE
        Logger.debug(TAG, "start done: BT state=$bluetoothState")
    }

    /**
     * Stops and closes all components related to Bluetooth audio.
     */
    fun stop() {
        ThreadUtils.checkIsOnMainThread()
        Logger.debug(TAG, "stop: BT state=$bluetoothState")
        if (bluetoothAdapter == null) return
        // Stop BT SCO connection with remote device if needed.
        stopScoAudio()
        // Close down remaining BT resources.
//        if (bluetoothState == State.UNINITIALIZED) return
        unregisterReceiver(bluetoothHeadsetReceiver)
        cancelTimer()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
        bluetoothHeadset = null
        bluetoothAdapter = null
        bluetoothDevice = null
        bluetoothState = State.UNINITIALIZED
        Logger.debug(TAG, "stop done: BT state=$bluetoothState")
    }

    /**
     * Starts Bluetooth SCO connection with remote device.
     * Note that the phone application always has the priority on the usage of the SCO connection
     * for telephony. If this method is called while the phone is in call it will be ignored.
     * Similarly, if a call is received or sent while an application is using the SCO connection,
     * the connection will be lost for the application and NOT returned automatically when the call
     * ends. Also note that: up to and including API version JELLY_BEAN_MR1, this method initiates a
     * virtual voice call to the Bluetooth headset. After API version JELLY_BEAN_MR2 only a raw SCO
     * audio connection is established.
     * TODO(henrika): should we add support for virtual voice call to BT headset also for JBMR2 and
     *  higher. It might be required to initiates a virtual voice call since many devices do not
     *  accept SCO audio without a "call".
     */
    fun startScoAudio(): Boolean {
        ThreadUtils.checkIsOnMainThread()
        Logger.debug(TAG, "startSco(): " +
                "BT state=$bluetoothState, " +
                "attempts: $scoConnectionAttempts, " +
                "SCO is on: $isScoOn")
        if (scoConnectionAttempts >= MAX_SCO_CONNECTION_ATTEMPTS) {
            Logger.error(TAG, "BT SCO connection fails - no more attempts")
            return false
        }
        if (bluetoothState != State.HEADSET_AVAILABLE) {
            Logger.error(TAG, "BT SCO connection fails - no headset available")
            return false
        }
        // Start BT SCO channel and wait for ACTION_AUDIO_STATE_CHANGED.
        Logger.debug(TAG, "Starting Bluetooth SCO and waits for ACTION_AUDIO_STATE_CHANGED...")
        // The SCO connection establishment can take several seconds, hence we cannot rely on the
        // connection to be available when the method returns but instead register to receive the
        // intent ACTION_SCO_AUDIO_STATE_UPDATED and wait for the state to be SCO_AUDIO_STATE_CONNECTED.
        bluetoothState = State.SCO_CONNECTING
        audioManager?.startBluetoothSco()
        audioManager?.isBluetoothScoOn = true
        scoConnectionAttempts++
        startTimer()
        Logger.debug(TAG, "startScoAudio done: " +
                "BT state=$bluetoothState, " +
                "SCO is on: $isScoOn")
        return true
    }

    /**
     * Stops Bluetooth SCO connection with remote device
     */
    fun stopScoAudio() {
        ThreadUtils.checkIsOnMainThread()
        Logger.debug(TAG, "stopScoAudio: BT state=$bluetoothState, SCO is on: $isScoOn")
        if (bluetoothState != State.SCO_CONNECTING && bluetoothState != State.SCO_CONNECTED) return
        cancelTimer()
        audioManager?.stopBluetoothSco()
        audioManager?.isBluetoothScoOn = false
        bluetoothState = State.SCO_DISCONNECTING
        Logger.debug(TAG, "stopScoAudio done: BT state=$bluetoothState, SCO is on: $isScoOn")
    }

    /**
     * Use the BluetoothHeadset proxy object (controls the Bluetooth Headset
     * Service via IPC) to update the list of connected devices for the HEADSET
     * profile. The internal state will change to HEADSET_UNAVAILABLE or to
     * HEADSET_AVAILABLE and |bluetoothDevice| will be mapped to the connected
     * device if available.
     */
    fun updateDevice() {
        if (bluetoothState == State.UNINITIALIZED || bluetoothHeadset == null) return
        Logger.debug(TAG, "updateDevice")
        // Get connected devices for the headset profile. Returns the set of
        // devices which are in state STATE_CONNECTED. The BluetoothDevice class
        // is just a thin wrapper for a Bluetooth hardware address.
        val devices = bluetoothHeadset?.connectedDevices ?: emptyList()
        if (devices.isEmpty()) {
            bluetoothDevice = null
            bluetoothState = State.HEADSET_UNAVAILABLE
            Logger.debug(TAG, "No connected bluetooth headset")
        } else {
            // Always use first device in list. Android only supports one device.
            bluetoothDevice = devices[0]
            bluetoothState = State.HEADSET_AVAILABLE
            Logger.debug(TAG, "Connected bluetooth headset: " +
                    "name=${bluetoothDevice?.name}, " +
                    "state=${stateToString(bluetoothHeadset?.getConnectionState(bluetoothDevice))}, " +
                    "SCO audio=${bluetoothHeadset?.isAudioConnected(bluetoothDevice)}"
            )
        }
        Logger.debug(TAG, "updateDevice done: BT state=$bluetoothState")
    }

    /**
     * Stubs for test mocks.
     */
    protected fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter): Intent? =
        context.registerReceiver(receiver, filter)

    protected fun unregisterReceiver(receiver: BroadcastReceiver) =
        context.unregisterReceiver(receiver)

    protected fun getBluetoothProfileProxy(
        context: Context,
        listener: ServiceListener,
        profile: Int
    ): Boolean = bluetoothAdapter?.getProfileProxy(context, listener, profile) == true

    protected fun hasPermission(context: Context, permission: String): Boolean =
        context.checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED

    /**
     * Logs the state of the local Bluetooth adapter.
     */
    protected fun logBluetoothAdapterInfo(localAdapter: BluetoothAdapter) {
        if (hasPermission(context, Manifest.permission.BLUETOOTH)) {
            Logger.debug(TAG, "BluetoothAdapter: " +
                    "enabled=${localAdapter.isEnabled}, " +
                    "state=${stateToString(localAdapter.state)}, " +
                    "name=${localAdapter.name}, " +
                    "address=${localAdapter.address}"
            )
        }
        // Log the set of BluetoothDevice objects that are bonded (paired) to the local adapter.
        val pairedDevices = localAdapter.bondedDevices
        if (pairedDevices.isNotEmpty()) {
            Logger.debug(TAG, "paired devices:")
            for (device in pairedDevices) {
                Logger.debug(TAG, "name=" + device.name + ", address=" + device.address)
            }
        }
    }

    /**
     * Ensures that the audio manager updates its list of available audio devices.
     */
    private fun updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread()
        Logger.debug(TAG, "updateAudioDeviceState")
        rtcAudioManager.updateAudioDeviceState()
    }

    /**
     * Starts timer which times out after BLUETOOTH_SCO_TIMEOUT_MS milliseconds.
     */
    private fun startTimer() {
        ThreadUtils.checkIsOnMainThread()
        Logger.debug(TAG, "startTimer")
        handler.postDelayed(bluetoothTimeoutRunnable, BLUETOOTH_SCO_TIMEOUT_MS.toLong())
    }

    /**
     * Cancels any outstanding timer tasks.
     */
    private fun cancelTimer() {
        ThreadUtils.checkIsOnMainThread()
        Logger.debug(TAG, "cancelTimer")
        handler.removeCallbacks(bluetoothTimeoutRunnable)
    }

    /**
     * Called when start of the BT SCO channel takes too long time. Usually
     * happens when the BT device has been turned on during an ongoing call.
     */
    private fun bluetoothTimeout() {
        ThreadUtils.checkIsOnMainThread()
        if (bluetoothState == State.UNINITIALIZED || bluetoothHeadset == null) {
            return
        }
        Logger.debug(TAG, "bluetoothTimeout: " +
                "BT state=" + bluetoothState + ", " +
                "attempts: $scoConnectionAttempts, " +
                "SCO is on: $isScoOn")
        if (bluetoothState != State.SCO_CONNECTING) {
            return
        }
        // Bluetooth SCO should be connecting; check the latest result.
        var scoConnected = false
        val devices = bluetoothHeadset?.connectedDevices ?: emptyList()
        if (devices.isNotEmpty()) {
            bluetoothDevice = devices[0]
            if (bluetoothHeadset?.isAudioConnected(bluetoothDevice) == true) {
                Logger.debug(TAG, "SCO connected with ${bluetoothDevice?.name}")
                scoConnected = true
            } else {
                Logger.debug(TAG, "SCO is not connected with ${bluetoothDevice?.name}")
            }
        }
        if (scoConnected) {
            // We thought BT had timed out, but it's actually on; updating state.
            bluetoothState = State.SCO_CONNECTED
            scoConnectionAttempts = 0
        } else {
            // Give up and "cancel" our request by calling stopBluetoothSco().
            Log.w(TAG, "BT failed to connect after timeout")
            stopScoAudio()
        }
        updateAudioDeviceState()
        Logger.debug(TAG, "bluetoothTimeout done: BT state=$bluetoothState")
    }

    /**
     * Checks whether audio uses Bluetooth SCO.
     */
    private val isScoOn: Boolean
        get() = audioManager?.isBluetoothScoOn == true

    /**
     * Converts BluetoothAdapter states into local string representations.
     */
    private fun stateToString(state: Int?): String {
        return when (state) {
            BluetoothAdapter.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothAdapter.STATE_CONNECTED -> "CONNECTED"
            BluetoothAdapter.STATE_CONNECTING -> "CONNECTING"
            BluetoothAdapter.STATE_DISCONNECTING -> "DISCONNECTING"
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_ON -> "ON"
            // Indicates the local Bluetooth adapter is turning off. Local clients should immediately
            // attempt graceful disconnection of any remote links.
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            // Indicates the local Bluetooth adapter is turning on. However local clients should wait
            // for STATE_ON before attempting to use the adapter.
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            else -> "INVALID"
        }
    }

}