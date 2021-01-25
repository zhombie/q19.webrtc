package kz.q19.webrtc.core.ui

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.Keep

@Keep
open class SurfaceViewRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : org.webrtc.SurfaceViewRenderer(context, attrs)