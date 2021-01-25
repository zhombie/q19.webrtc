package kz.q19.webrtc.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import kz.q19.webrtc.utils.AssertUtils.assertIsTrue
import kz.q19.webrtc.utils.Logger
import kz.q19.webrtc.utils.ThreadUtils.threadInfo
import org.webrtc.ThreadUtils.ThreadChecker

/**
 * [RTCProximitySensor] manages functions related to the proximity sensor.
 * On most device, the proximity sensor is implemented as a boolean-sensor.
 * It returns just two values "NEAR" or "FAR". Thresholding is done on the LUX
 * value i.e. the LUX value of the light sensor is compared with a threshold.
 * A LUX-value more than the threshold means the proximity sensor returns "FAR".
 * Anything less than the threshold value and the sensor  returns "NEAR".
 */
internal class RTCProximitySensor private constructor(
    context: Context,
    sensorStateListener: Runnable
) : SensorEventListener {

    companion object {
        private val TAG = RTCProximitySensor::class.java.simpleName

        /** Construction  */
        fun create(context: Context, sensorStateListener: Runnable): RTCProximitySensor {
            return RTCProximitySensor(context, sensorStateListener)
        }
    }

    // This class should be created, started and stopped on one thread
    // (e.g. the main thread). We use |nonThreadSafe| to ensure that this is
    // the case. Only active when |DEBUG| is set to true.
    private val threadChecker = ThreadChecker()

    private val onSensorStateListener: Runnable?
    private val sensorManager: SensorManager?
    private var proximitySensor: Sensor? = null
    private var lastStateReportIsNear = false

    init {
        Logger.debug(TAG, "RTCProximitySensor: $threadInfo")
        onSensorStateListener = sensorStateListener
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager?
    }

    /**
     * Activate the proximity sensor. Also do initialization if called for the
     * first time.
     */
    fun start(): Boolean {
        threadChecker.checkIsOnValidThread()
        Logger.debug(TAG, "start: $threadInfo")
        if (!initDefaultSensor()) {
            // Proximity sensor is not supported on this device.
            return false
        }
        sensorManager?.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        return true
    }

    /**
     * Deactivate the proximity sensor.
     */
    fun stop() {
        threadChecker.checkIsOnValidThread()
        Logger.debug(TAG, "stop: $threadInfo")
        if (proximitySensor == null) {
            return
        }
        sensorManager?.unregisterListener(this, proximitySensor)
    }

    /**
     * Getter for last reported state. Set to true if "near" is reported.
     */
    fun sensorReportsNearState(): Boolean {
        threadChecker.checkIsOnValidThread()
        return lastStateReportIsNear
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        threadChecker.checkIsOnValidThread()
        assertIsTrue(sensor.type == Sensor.TYPE_PROXIMITY)
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Logger.error(TAG, "The values returned by this sensor cannot be trusted")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        threadChecker.checkIsOnValidThread()
        assertIsTrue(event.sensor.type == Sensor.TYPE_PROXIMITY)
        // As a best practice; do as little as possible within this method and
        // avoid blocking.
        val distanceInCentimeters = event.values[0]
        if (proximitySensor != null) {
            lastStateReportIsNear = if (distanceInCentimeters < proximitySensor?.maximumRange ?: 0F) {
                Logger.debug(TAG, "Proximity sensor => NEAR state")
                true
            } else {
                Logger.debug(TAG, "Proximity sensor => FAR state")
                false
            }
        }

        // Report about new state to listening client. Client can then call
        // sensorReportsNearState() to query the current state (NEAR or FAR).
        onSensorStateListener?.run()

        Logger.debug(TAG, "onSensorChanged: $threadInfo: " +
                "accuracy=${event.accuracy}, timestamp=${event.timestamp}, distance=${event.values[0]}")
    }

    /**
     * Get default proximity sensor if it exists. Tablet devices (e.g. Nexus 7)
     * does not support this type of sensor and false will be returned in such
     * cases.
     */
    private fun initDefaultSensor(): Boolean {
        if (proximitySensor != null) {
            return true
        }
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor == null) {
            return false
        }
        logProximitySensorInfo()
        return true
    }

    /**
     * Helper method for logging information about the proximity sensor.
     */
    private fun logProximitySensorInfo() {
        if (proximitySensor == null) {
            return
        }
        val info = StringBuilder("Proximity sensor: ")
        info.append("name=").append(proximitySensor?.name)
        info.append(", vendor: ").append(proximitySensor?.vendor)
        info.append(", power: ").append(proximitySensor?.power)
        info.append(", resolution: ").append(proximitySensor?.resolution)
        info.append(", max range: ").append(proximitySensor?.maximumRange)
        info.append(", min delay: ").append(proximitySensor?.minDelay)
        // Added in API level 20.
        info.append(", type: ").append(proximitySensor?.stringType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Added in API level 21.
            info.append(", max delay: ").append(proximitySensor?.maxDelay)
            info.append(", reporting mode: ").append(proximitySensor?.reportingMode)
            info.append(", isWakeUpSensor: ").append(proximitySensor?.isWakeUpSensor)
        }
        Logger.debug(TAG, info.toString())
    }

}