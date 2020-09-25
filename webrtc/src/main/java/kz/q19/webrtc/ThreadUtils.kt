package kz.q19.webrtc

object ThreadUtils {

    /**
     * Helper method for building a string of thread information.
     */
    @JvmStatic
    val threadInfo: String?
        get() = "@[name=" + Thread.currentThread().name + ", id=" + Thread.currentThread().id + "]"

}