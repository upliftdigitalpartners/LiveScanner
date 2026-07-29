package dev.fahim.livescanner.playback

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * A rolling 30-minute window of the live stream, kept as the *compressed* bytes coming off the
 * wire. Storing MP3/AAC frames rather than decoded PCM is what makes 30 minutes affordable:
 * roughly 7 MB at 32 kbps, against ~58 MB for 16 kHz mono PCM.
 *
 * Backed by a fixed-size file used as a ring. [totalWritten] keeps counting past the ring size, so
 * a caller can hold a stable "absolute offset" and ask later whether it is still in the window.
 *
 * Cutting the stream at an arbitrary byte offset yields a clip whose first frame is usually
 * partial; MP3 and ADTS decoders resync at the next frame header, which costs a few milliseconds
 * at the head of a replay and is not audible.
 */
class AudioBuffer(context: Context) {

    private val file = File(context.cacheDir, "stream_ring.bin")
    private val lock = Any()

    private var raf: RandomAccessFile? = null

    /** Monotonic count of every byte ever written — never wraps. */
    @Volatile
    var totalWritten: Long = 0L
        private set

    /** (wall clock ms, absolute byte offset) samples, ~1/second, for time↔offset mapping. */
    private val index = ArrayDeque<Pair<Long, Long>>()

    /** Absolute offset of the oldest byte still retained. */
    val oldestRetained: Long
        get() = (totalWritten - CAPACITY).coerceAtLeast(0L)

    fun open() {
        synchronized(lock) {
            if (raf != null) return
            try {
                raf = RandomAccessFile(file, "rw").apply { setLength(CAPACITY) }
                totalWritten = 0L
                index.clear()
            } catch (t: Throwable) {
                Log.w(TAG, "buffer open failed", t)
                raf = null
            }
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { raf?.close() }
            raf = null
        }
    }

    fun reset() {
        synchronized(lock) {
            totalWritten = 0L
            index.clear()
        }
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            val f = raf ?: return
            try {
                var written = 0
                while (written < length) {
                    val pos = ((totalWritten + written) % CAPACITY).toInt()
                    val chunk = minOf(length - written, CAPACITY.toInt() - pos)
                    f.seek(pos.toLong())
                    f.write(data, offset + written, chunk)
                    written += chunk
                }
                totalWritten += length
                stampIndex()
            } catch (t: Throwable) {
                Log.w(TAG, "buffer write failed", t)
            }
        }
    }

    private fun stampIndex() {
        val now = System.currentTimeMillis()
        val last = index.lastOrNull()
        if (last == null || now - last.first >= INDEX_INTERVAL_MS) {
            index.addLast(now to totalWritten)
            while (index.size > INDEX_MAX) index.removeFirst()
        }
    }

    /** Reads [length] bytes starting at absolute [from], or null if that span has aged out. */
    fun read(from: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        synchronized(lock) {
            val f = raf ?: return null
            if (from < oldestRetained || from + length > totalWritten) return null
            return try {
                val out = ByteArray(length)
                var read = 0
                while (read < length) {
                    val pos = ((from + read) % CAPACITY).toInt()
                    val chunk = minOf(length - read, CAPACITY.toInt() - pos)
                    f.seek(pos.toLong())
                    f.readFully(out, read, chunk)
                    read += chunk
                }
                out
            } catch (t: Throwable) {
                Log.w(TAG, "buffer read failed", t)
                null
            }
        }
    }

    /**
     * The most recent [millis] of audio, with the absolute offset it starts at, or null when the
     * stream has not yet produced that much. Used both for live transcription and for carving a
     * transmission's clip out of the window.
     */
    fun latest(millis: Long): Segment? {
        synchronized(lock) {
            if (raf == null) return null
            val cutoff = System.currentTimeMillis() - millis
            val start = index.firstOrNull { it.first >= cutoff }?.second
                ?: return null
            val length = (totalWritten - start).toInt()
            if (length <= 0) return null
            val bytes = read(start, length) ?: return null
            return Segment(start, bytes)
        }
    }

    /** Bytes for a previously recorded span, for REPLAY and CLIP. Null once it has aged out. */
    fun segment(from: Long, length: Int): ByteArray? = read(from, length)

    /** Observed bytes per second, used to convert clip lengths to durations. */
    fun bytesPerSecond(): Double {
        synchronized(lock) {
            val first = index.firstOrNull() ?: return DEFAULT_BPS
            val last = index.lastOrNull() ?: return DEFAULT_BPS
            val seconds = (last.first - first.first) / 1000.0
            if (seconds < 1.0) return DEFAULT_BPS
            return ((last.second - first.second) / seconds).coerceAtLeast(1.0)
        }
    }

    data class Segment(val offset: Long, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Segment && other.offset == offset && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * offset.hashCode() + bytes.contentHashCode()
    }

    private companion object {
        const val TAG = "AudioBuffer"
        /** 16 MB covers 30 minutes at up to ~70 kbps; typical ATC feeds run far below that. */
        const val CAPACITY = 16L * 1024 * 1024
        const val INDEX_INTERVAL_MS = 1_000L
        const val INDEX_MAX = 2_400
        const val DEFAULT_BPS = 4_000.0 // 32 kbps
    }
}
