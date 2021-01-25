package kz.q19.webrtc.core.constraints

/**
 * Integer value based audio constraints.
 *
 * @see <a href="https://chromium.googlesource.com/external/webrtc/+/e33c5d918a213202321bde751226c4949644fe5e/webrtc/api/mediaconstraintsinterface.cc">
 *     Available constraints in media constraints interface implementation</a>
 */
enum class AudioIntegerConstraints constructor(
    override val constraint: String
) : RTCConstraint<Int> {
    LEVEL_CONTROL_INITIAL_PEAK_LEVEL_DBFS("levelControlInitialPeakLevelDBFS"),
}