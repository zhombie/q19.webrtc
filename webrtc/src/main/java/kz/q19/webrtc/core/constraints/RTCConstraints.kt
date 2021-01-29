/* Reference: https://github.com/netguru/videochatguru-android */

package kz.q19.webrtc.core.constraints

import org.webrtc.MediaConstraints

/**
 * Container class for WebRTC constraints management.
 */
open class RTCConstraints<T : RTCConstraint<E>, E> {

    private val mandatory: MutableMap<RTCConstraint<E>, E> = mutableMapOf()
    private val optional: MutableMap<RTCConstraint<E>, E> = mutableMapOf()

    internal val mandatoryKeyValuePairs: List<MediaConstraints.KeyValuePair>
        get() = toKeyValuePairs(mandatory)

    internal val optionalKeyValuePairs: List<MediaConstraints.KeyValuePair>
        get() = toKeyValuePairs(optional)


    /**
     * Adds all constraints. If constraints are duplicated value from inserted collection will be used.
     * @see [addAll]
     */
    operator fun plusAssign(other: RTCConstraints<T, E>): Unit = addAll(other)

    /**
     * Adds mandatory constraint. If constraint is already present new value will be used.
     */
    fun addMandatoryConstraint(constraint: T, value: E): E? {
        return mandatory.put(constraint, value)
    }

    /**
     * Adds optional constraint. If constraint is already present new value will be used.
     */
    fun addOptionalConstraint(constraint: T, value: E): E? {
        return optional.put(constraint, value)
    }

    /**
     * Adds all constraints. If constraints are duplicated value from inserted collection will be used.
     */
    fun addAll(other: RTCConstraints<T, E>) {
        mandatory.putAll(other.mandatory)
        optional.putAll(other.optional)
    }

    fun clearAll() {
        mandatory.clear()
        optional.clear()
    }

    private fun toKeyValuePairs(constraintsMap: Map<RTCConstraint<E>, E>): List<MediaConstraints.KeyValuePair> =
        constraintsMap.map { (constraint, enabled) -> constraint.toKeyValuePair(enabled)
    }

}