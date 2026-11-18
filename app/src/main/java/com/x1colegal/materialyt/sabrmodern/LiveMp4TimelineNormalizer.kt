package com.x1colegal.materialyt.sabrmodern

import com.x1colegal.materialyt.AppLog

/**
 * Rewrites `tkhd` duration to `0` (so ExoPlayer `ProgressiveMediaSource` treats a growing live
 * fMP4 stream as `C.TIME_UNSET` rather than ending at the initial window) and normalizes `tfdt`
 * decode timestamps relative to the first live fragment so playback starts at `00:00`.
 */
internal class LiveMp4TimelineNormalizer {
    private var timescale: Long = 0L
    private var baseDecodeTime: Long? = null
    private var loggedInit = false

    fun normalize(source: ByteArray, label: String, itag: Int): ByteArray {
        val result = source.copyOf()
        fun uint32(offset: Int): Long =
            ((result[offset].toLong() and 0xff) shl 24) or
                ((result[offset + 1].toLong() and 0xff) shl 16) or
                ((result[offset + 2].toLong() and 0xff) shl 8) or
                (result[offset + 3].toLong() and 0xff)

        fun uint64(offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) value = (value shl 8) or (result[offset + index].toLong() and 0xff)
            return value
        }

        fun boxEnd(offset: Int, limit: Int): Pair<Int, Int>? {
            if (offset + 8 > limit) return null
            val smallSize = uint32(offset)
            val headerSize = if (smallSize == 1L) 16 else 8
            val size = when (smallSize) {
                0L -> (limit - offset).toLong()
                1L -> if (offset + 16 <= limit) uint64(offset + 8) else return null
                else -> smallSize
            }
            if (size < headerSize || size > limit - offset) return null
            return (offset + size.toInt()) to headerSize
        }

        fun writeUnsigned(offset: Int, value: Long, bytes: Int) {
            for (index in 0 until bytes) {
                val shift = (bytes - index - 1) * 8
                result[offset + index] = (value ushr shift).toByte()
            }
        }

        fun visit(start: Int, limit: Int, parent: String) {
            var offset = start
            while (offset + 8 <= limit) {
                val (end, headerSize) = boxEnd(offset, limit) ?: break
                val type = String(result, offset + 4, 4, Charsets.US_ASCII)
                if (type == "moov" || type == "trak" || type == "mdia" || type == "moof" || type == "traf") {
                    visit(offset + headerSize, end, type)
                } else if (type == "mdhd" && parent == "mdia" && offset + headerSize + 20 <= end) {
                    val version = result[offset + headerSize].toInt() and 0xff
                    val timescaleOffset = offset + headerSize + if (version == 1) 20 else 12
                    if (timescaleOffset + 4 <= end) {
                        val scale = uint32(timescaleOffset)
                        if (scale > 0L) timescale = scale
                    }
                } else if (type == "tkhd" && parent == "trak" && offset + headerSize + 24 <= end) {
                    val version = result[offset + headerSize].toInt() and 0xff
                    val durationOffset = offset + headerSize + if (version == 1) 28 else 20
                    val durationBytes = if (version == 1) 8 else 4
                    if (durationOffset + durationBytes <= end) {
                        val old = if (durationBytes == 8) uint64(durationOffset) else uint32(durationOffset)
                        writeUnsigned(durationOffset, 0L, durationBytes)
                        if (!loggedInit) {
                            loggedInit = true
                            AppLog.event("$label live init tkhd itag=$itag original=$old patched=0")
                        }
                    }
                } else if (type == "tfdt" && parent == "traf" && offset + headerSize + 8 <= end) {
                    val version = result[offset + headerSize].toInt() and 0xff
                    val valueOffset = offset + headerSize + 4
                    val bytes = if (version == 1) 8 else 4
                    if (valueOffset + bytes <= end) {
                        val absolute = if (bytes == 8) uint64(valueOffset) else uint32(valueOffset)
                        val first = baseDecodeTime ?: absolute.also { baseDecodeTime = it }
                        writeUnsigned(valueOffset, (absolute - first).coerceAtLeast(0L), bytes)
                    }
                }
                offset = end
            }
        }

        visit(0, result.size, "root")
        return result
    }
}
