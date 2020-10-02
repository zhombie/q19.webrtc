package kz.q19.webrtc.core

import android.content.Context
import android.util.AttributeSet
import org.webrtc.SurfaceViewRenderer

class WebRTCSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceViewRenderer(context, attrs)