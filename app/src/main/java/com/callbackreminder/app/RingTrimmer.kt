package com.callbackreminder.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Cuts the ringing from the start of an outgoing call's recording.
 *
 * Android does not tell apps the moment the other person picks up, so an outgoing call is recorded
 * from the moment you dial. After the call, the call log says how long you really talked; everything
 * before that (minus a one-second margin) is ringing and gets cut. The audio is copied as-is, not
 * re-encoded. A new file is written first and the original is only deleted once that worked, so any
 * problem simply keeps the full recording.
 */
object RingTrimmer {
    private const val MARGIN_MS = 1_000L
    /** Not worth a new file for less than this. */
    private const val MIN_CUT_MS = 1_500L

    /** Returns true if the recording was replaced by a trimmed copy. */
    fun trim(ctx: Context, r: Recording, talkSec: Long): Boolean {
        if (talkSec <= 0L) return false
        val extractor = MediaExtractor()
        var out: RecordingFiles.Output? = null
        var muxer: MediaMuxer? = null
        var done = false
        try {
            extractor.setDataSource(ctx, Uri.parse(r.uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(track)
            if (!format.containsKey(MediaFormat.KEY_DURATION)) return false
            val totalUs = format.getLong(MediaFormat.KEY_DURATION)
            val cutUs = totalUs - (talkSec * 1000L + MARGIN_MS) * 1000L
            if (cutUs < MIN_CUT_MS * 1000L) return false

            extractor.selectTrack(track)
            extractor.seekTo(cutUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val original = RecordingFiles.displayName(ctx, r.uri) ?: return false
            val baseName = original.removeSuffix(".m4a")
            val o = RecordingFiles.create(ctx, baseName + "_trim") ?: return false
            out = o
            val m = if (o.pfd != null) {
                MediaMuxer(o.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                MediaMuxer(o.file!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            muxer = m
            val dst = m.addTrack(format)
            m.start()

            val bufSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxOf(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 64 * 1024)
            } else {
                256 * 1024
            }
            val buf = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()
            var firstUs = -1L
            var lastUs = 0L
            var samples = 0
            while (true) {
                buf.clear()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (firstUs < 0L) firstUs = t
                lastUs = t - firstUs
                info.set(
                    0,
                    size,
                    lastUs,
                    if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                )
                m.writeSampleData(dst, buf, info)
                samples++
                extractor.advance()
            }
            m.stop()
            m.release()
            muxer = null
            o.pfd?.close()
            if (samples == 0) return false

            // The trimmed copy is complete: swap it in for the original.
            RecordingFiles.publish(ctx, o)
            val newSize = RecordingFiles.size(ctx, o)
            RecordingPlayer.stopIf(r.id)
            RecordingFiles.delete(ctx, r.uri)
            val finalUri = RecordingFiles.rename(ctx, o, original)
            RecordingStore.replaceAudio(
                r.id,
                finalUri.toString(),
                durationSec = lastUs / 1_000_000L,
                sizeBytes = newSize,
            )
            done = true
            return true
        } catch (e: Throwable) {
            return false
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {
                // ignore
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                // ignore
            }
            if (!done) {
                out?.let { o ->
                    try {
                        o.pfd?.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                    RecordingFiles.delete(ctx, o.uri.toString())
                }
            }
        }
    }
}
