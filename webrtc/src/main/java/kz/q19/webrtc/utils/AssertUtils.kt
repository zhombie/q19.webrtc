package kz.q19.webrtc.utils

internal object AssertUtils {

    /**
     * Helper method which throws an exception when an assertion has failed
     */
    @JvmStatic
    fun assertIsTrue(condition: Boolean) {
        if (!condition) {
            throw AssertionError("Expected condition to be true")
        }
    }

}