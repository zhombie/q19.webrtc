package kz.q19.webrtc.utils

import android.util.Log
import java.util.*
import java.util.regex.Pattern

object CodecUtils {
    private val TAG = CodecUtils::class.java.simpleName

    const val AUDIO_CODEC_OPUS = "OPUS"
    const val AUDIO_CODEC_ISAC = "ISAC"
    const val VIDEO_CODEC_VP9 = "VP9"

    private const val VIDEO_CODEC_PARAM_START_BITRATE = "x-google-start-bitrate"
    private const val AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate"

    fun setStartBitrate(
        codec: String,
        isVideoCodec: Boolean,
        sdpDescription: String,
        bitrateKbps: Int
    ): String {
        val lines = sdpDescription.split("\r\n").toTypedArray()
        var rtpmapLineIndex = -1
        var sdpFormatUpdated = false
        var codecRtpMap: String? = null
        // Search for codec rtpmap in format
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        var regex = "^a=rtpmap:(\\d+) $codec(/\\d+)+[\r]?$"
        var codecPattern = Pattern.compile(regex)
        for (i in lines.indices) {
            val codecMatcher = codecPattern.matcher(lines[i])
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1)
                rtpmapLineIndex = i
                break
            }
        }
        if (codecRtpMap == null) {
            Log.w(TAG, "No rtpmap for $codec codec")
            return sdpDescription
        }
        Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + " at " + lines[rtpmapLineIndex])

        // Check if a=fmtp string already exist in remote SDP for this codec and
        // update it with new bitrate parameter.
        regex = "^a=fmtp:$codecRtpMap \\w+=\\d+.*[\r]?$"
        codecPattern = Pattern.compile(regex)
        for (i in lines.indices) {
            val codecMatcher = codecPattern.matcher(lines[i])
            if (codecMatcher.matches()) {
                Log.d(TAG, "Found " + codec + " " + lines[i])
                if (isVideoCodec) {
                    lines[i] += "; $VIDEO_CODEC_PARAM_START_BITRATE=$bitrateKbps"
                } else {
                    lines[i] += "; " + AUDIO_CODEC_PARAM_BITRATE + "=" + bitrateKbps * 1000
                }
                Log.d(TAG, "Update remote SDP line: " + lines[i])
                sdpFormatUpdated = true
                break
            }
        }

        val newSdpDescription = StringBuilder()
        for (i in lines.indices) {
            newSdpDescription.append(lines[i]).append("\r\n")
            // Append new a=fmtp line if no such line exist for a codec.
            if (!sdpFormatUpdated && i == rtpmapLineIndex) {
                val bitrateSet: String = if (isVideoCodec) {
                    "a=fmtp:$codecRtpMap $VIDEO_CODEC_PARAM_START_BITRATE=$bitrateKbps"
                } else {
                    ("a=fmtp:" + codecRtpMap + " " + AUDIO_CODEC_PARAM_BITRATE + "=" + bitrateKbps * 1000)
                }
                Log.d(TAG, "Add remote SDP line: $bitrateSet")
                newSdpDescription.append(bitrateSet).append("\r\n")
            }
        }
        return newSdpDescription.toString()
    }

    fun preferCodec(sdpDescription: String, codec: String, isAudio: Boolean): String {
        val lines = sdpDescription.split("\r\n").toTypedArray()
        val mLineIndex = findMediaDescriptionLine(isAudio, lines)
        if (mLineIndex == -1) {
            Log.w(TAG, "No mediaDescription line, so can't prefer $codec")
            return sdpDescription
        }
        // A list with all the payload types with name |codec|. The payload types are integers in the
        // range 96-127, but they are stored as strings here.
        val codecPayloadTypes = ArrayList<String?>()
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        val codecPattern = Pattern.compile("^a=rtpmap:(\\d+) $codec(/\\d+)+[\r]?$")
        for (line in lines) {
            val codecMatcher = codecPattern.matcher(line)
            if (codecMatcher.matches()) {
                codecPayloadTypes.add(codecMatcher.group(1))
            }
        }
        if (codecPayloadTypes.isEmpty()) {
            Log.w(TAG, "No payload types with name $codec")
            return sdpDescription
        }

        val newMLine = movePayloadTypesToFront(codecPayloadTypes, lines[mLineIndex]) ?: return sdpDescription
        Log.d(TAG, "Change media description from: " + lines[mLineIndex] + " to " + newMLine)
        lines[mLineIndex] = newMLine
        return joinString(lines.toList(), "\r\n", true /* delimiterAtEnd */)
    }

    /** Returns the line number containing "m=audio|video", or -1 if no such line exists.  */
    private fun findMediaDescriptionLine(isAudio: Boolean, sdpLines: Array<String>): Int {
        val mediaDescription = if (isAudio) "m=audio " else "m=video "
        for (i in sdpLines.indices) {
            if (sdpLines[i].startsWith(mediaDescription)) {
                return i
            }
        }
        return -1
    }

    private fun movePayloadTypesToFront(
        preferredPayloadTypes: List<String?>,
        mLine: String
    ): String? {
        // The format of the media description line should be: m=<media> <port> <proto> <fmt> ...
        val origLineParts = listOf(*mLine.split(" ").toTypedArray())
        if (origLineParts.size <= 3) {
            Log.e(TAG, "Wrong SDP media description format: $mLine")
            return null
        }
        val header: List<String?> = origLineParts.subList(0, 3)
        val unpreferredPayloadTypes = ArrayList(origLineParts.subList(3, origLineParts.size))
        unpreferredPayloadTypes.removeAll(preferredPayloadTypes)
        // Reconstruct the line with |preferredPayloadTypes| moved to the beginning of the payload
        // types.
        val newLineParts: MutableList<String?> = ArrayList()
        newLineParts.addAll(header)
        newLineParts.addAll(preferredPayloadTypes)
        newLineParts.addAll(unpreferredPayloadTypes)
        return joinString(newLineParts, " ", false /* delimiterAtEnd */)
    }

    private fun joinString(
        s: Iterable<CharSequence?>,
        delimiter: String,
        delimiterAtEnd: Boolean
    ): String {
        val iterator = s.iterator()
        if (!iterator.hasNext()) {
            return ""
        }
        val buffer = StringBuilder(iterator.next() ?: return "")
        while (iterator.hasNext()) {
            buffer.append(delimiter).append(iterator.next())
        }
        if (delimiterAtEnd) {
            buffer.append(delimiter)
        }
        return buffer.toString()
    }
}