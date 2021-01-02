package kz.q19.webrtc.core

import kz.q19.webrtc.utils.Logger
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class ProxyVideoSink constructor(private val tag: String = "ProxyVideoSink") : VideoSink {
    private var target: VideoSink? = null

    @Synchronized
    override fun onFrame(frame: VideoFrame?) {
        if (target == null) {
            Logger.error(tag, "Dropping frame in proxy because target is null.")
        } else {
            target?.onFrame(frame)
        }
    }

    @Synchronized
    fun setTarget(target: VideoSink?): Boolean {
        this.target = target
        return this.target != null
    }
}