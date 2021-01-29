package kz.q19.webrtc.utils

import android.os.Build
import android.util.Log

internal object Logger {

    private const val LIMIT = 4000

    fun debug(tag: String, message: String) {
        if (message.length > LIMIT) {
            Log.d(tag, message.substring(0, LIMIT))
            debug(tag, message.substring(LIMIT))
        } else {
            Log.d(tag, message)
        }
    }

    fun error(tag: String, message: String) {
        if (message.length > LIMIT) {
            Log.e(tag, message.substring(0, LIMIT))
            error(tag, message.substring(LIMIT))
        } else {
            Log.e(tag, message)
        }
    }

    fun warn(tag: String, message: String) {
        if (message.length > LIMIT) {
            Log.w(tag, message.substring(0, LIMIT))
            warn(tag, message.substring(LIMIT))
        } else {
            Log.w(tag, message)
        }
    }

    /** Information about the current build, taken from system properties.  */
    @JvmStatic
    fun logDeviceInfo(tag: String) {
        Log.d(
            tag, "Android SDK: " + Build.VERSION.SDK_INT + ", "
                    + "Release: " + Build.VERSION.RELEASE + ", "
                    + "Brand: " + Build.BRAND + ", "
                    + "Device: " + Build.DEVICE + ", "
                    + "Id: " + Build.ID + ", "
                    + "Hardware: " + Build.HARDWARE + ", "
                    + "Manufacturer: " + Build.MANUFACTURER + ", "
                    + "Model: " + Build.MODEL + ", "
                    + "Product: " + Build.PRODUCT
        )
    }

}