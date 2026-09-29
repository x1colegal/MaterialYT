package com.x1colegal.materialyt

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import java.io.IOException

/** Presents consecutive bounded HTTP range requests as one continuous source. */
class ChunkedDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val chunkSize: Long = 9L * 1024L * 1024L
) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream)
    }

    private val listeners = mutableListOf<TransferListener>()
    private var upstream: DataSource? = null
    private var original: DataSpec? = null
    private var offset = 0L
    private var chunkRead = 0L
    private var chunkLength = 0L
    private var lastChunk = false

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        original = dataSpec
        offset = 0L
        lastChunk = false
        openNextChunk()
        return dataSpec.length
    }

    @Throws(IOException::class)
    private fun openNextChunk() {
        upstream?.close()
        val spec = original ?: error("ChunkedDataSource is not open")
        val remaining = if (spec.length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else spec.length - offset
        if (remaining <= 0L) { lastChunk = true; return }
        val requested = minOf(chunkSize, remaining)
        val next = upstreamFactory.createDataSource().also { source -> listeners.forEach(source::addTransferListener) }
        val reportedLength = next.open(spec.subrange(offset, requested))
        upstream = next
        chunkRead = 0L
        chunkLength = if (reportedLength == C.LENGTH_UNSET.toLong()) requested else minOf(reportedLength, requested)
        lastChunk = remaining <= chunkSize || (reportedLength != C.LENGTH_UNSET.toLong() && reportedLength < requested)
    }

    override fun read(buffer: ByteArray, offsetInBuffer: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (chunkRead >= chunkLength) {
                if (lastChunk) return C.RESULT_END_OF_INPUT
                offset += chunkRead
                openNextChunk()
            }
            val read = upstream?.read(buffer, offsetInBuffer, minOf(length.toLong(), chunkLength - chunkRead).toInt())
                ?: return C.RESULT_END_OF_INPUT
            if (read != C.RESULT_END_OF_INPUT) {
                chunkRead += read
                return read
            }
            if (chunkRead < chunkLength) return C.RESULT_END_OF_INPUT
            if (lastChunk) return C.RESULT_END_OF_INPUT
            offset += chunkRead
            openNextChunk()
        }
    }

    override fun getUri(): Uri? = upstream?.uri ?: original?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders.orEmpty()

    override fun close() {
        upstream?.close()
        upstream = null
        original = null
    }
}
