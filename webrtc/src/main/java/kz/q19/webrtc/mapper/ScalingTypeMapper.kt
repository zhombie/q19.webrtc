package kz.q19.webrtc.mapper

import kz.q19.webrtc.core.ScalingType
import org.webrtc.RendererCommon

internal class ScalingTypeMapper {

    companion object {
        fun map(scalingType: RendererCommon.ScalingType): ScalingType {
            return when (scalingType) {
                RendererCommon.ScalingType.SCALE_ASPECT_FIT -> ScalingType.SCALE_ASPECT_FIT
                RendererCommon.ScalingType.SCALE_ASPECT_FILL -> ScalingType.SCALE_ASPECT_FILL
                RendererCommon.ScalingType.SCALE_ASPECT_BALANCED ->  ScalingType.SCALE_ASPECT_BALANCED
            }
        }

        fun map(scalingType: ScalingType): RendererCommon.ScalingType {
            return when (scalingType) {
                ScalingType.SCALE_ASPECT_FIT -> RendererCommon.ScalingType.SCALE_ASPECT_FIT
                ScalingType.SCALE_ASPECT_FILL -> RendererCommon.ScalingType.SCALE_ASPECT_FILL
                ScalingType.SCALE_ASPECT_BALANCED ->  RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
            }
        }
    }

}