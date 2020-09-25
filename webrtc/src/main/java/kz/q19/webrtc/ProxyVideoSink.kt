package kz.q19.webrtc

import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class ProxyVideoSink(private val tag: String = "ProxyVideoSink") : VideoSink {
    private var target: VideoSink? = null

    @Synchronized
    override fun onFrame(frame: VideoFrame?) {
        if (target == null) {
            Logger.debug(tag, "Dropping frame in proxy because target is null.")
            return
        }
        target?.onFrame(frame)
    }

    @Synchronized
    fun setTarget(target: VideoSink?) {
        this.target = target
    }
}