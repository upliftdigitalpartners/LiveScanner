package dev.fahim.livescanner.playback

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * A rolling 30-minute window of the live stream, kept as the *compressed* bytes coming off the
 * wire. Storing MP3/AAC frames rather than decoded PCM is what makes 30 minutes affordable:
 * roughly 7 MB at 32 kbps, against ~58 MB for 16 kHz mono PCM.
 *
 * There is one ring per feed, and each survives app restarts — tune away from a tower and back
 * and the last half hour is still there to scrub through. A sidecar index maps wall-clock time to
 * byte offset and is flushed periodically, so an unclean kill loses at most a minute of index.
 *
 * Cutting the stream at an arbitrary byte offset yields a clip whose first frame is usually
 * partial; MP3 and ADTS decoders resync at the next frame header, which costs a few milliseconds
 * at the head of a replay and is not audible.
 */
class AudioBuffer(context: Context) {

    private val dir = File(context.cacheDir, "rings").apply { mkdirs() }
    private val lock = Any()

    private var raf: RandomAccessFile? = null
    private var feedId: String? = null
    private var stampsSinceFlush = 0

    /** Monotonic count of every byte ever written to the current ring — never wraps. */
    @Volatile
    var totalWritten: Long = 0L
        private set

    /** (wall clock ms, absolute byte offset) samples, ~1/second, for time↔offset mapping. */
    private val index = ArrayDeque<Pair<Long, Long>>()

    /** Absolute offset of the oldest byte still retained. */
    val oldestRetained: Long
        get() = (totalWritten - CAPACITY).coerceAtLeast(0L)

    /** True when the ring holds audio from a previous session. */
    val hasHistory: Boolean
        get() = totalWritten > 0L

    /**
     * Points the buffer at [newFeedId]'s ring, saving whatever the previous feed had accumulated.
     * Tuning back to a feed restores its window rather than starting from nothing.
     */
    fun switchTo(newFeedId: String) {
        synchronized(lock) {
            if (feedId == newFeedId && raf != null) return
            closeLocked()
            feedId = newFeedId
            openLocked()
        }
    }

    /** Opens the current feed's ring if it isn't already; called by the data sink. */
    fun ensureOpen() {
        synchronized(lock) { if (raf == null && feedId != null) openLocked() }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun ringFile(id: String) = File(dir, "${safeName(id)}.bin")
    private fun indexFile(id: String) = File(dir, "${safeName(id)}.idx")

    private fun openLocked() {
        val id = feedId ?: return
        try {
            raf = RandomAccessFile(ringFile(id), "rw").apply { setLength(CAPACITY) }
            loadIndexLocked(id)
            pruneOldRings()
        } catch (t: Throwable) {
            Log.w(TAG, "buffer open failed", t)
            raf = null
            totalWritten = 0L
            index.clear()
        }
    }

    private fun closeLocked() {
        val id = feedId
        if (id != null && raf != null) saveIndexLocked(id)
        runCatching { raf?.close() }
        raf = null
        totalWritten = 0L
        index.clear()
        stampsSinceFlush = 0
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
                stampIndexLocked()
            } catch (t: Throwable) {
                Log.w(TAG, "buffer write failed", t)
            }
        }
    }

    private fun stampIndexLocked() {
        val now = System.currentTimeMillis()
        val last = index.lastOrNull()
        if (last != null && now - last.first < INDEX_INTERVAL_MS) return
        index.addLast(now to totalWritten)
        while (index.size > INDEX_MAX) index.removeFirst()
        if (++stampsSinceFlush >= FLUSH_EVERY) {
            stampsSinceFlush = 0
            feedId?.let { saveIndexLocked(it) }
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
     * stream has not yet produced that much.
     */
    fun latest(millis: Long): Segment? {
        synchronized(lock) {
            if (raf == null) return null
            val cutoff = System.currentTimeMillis() - millis
            val start = index.firstOrNull { it.first >= cutoff }?.second ?: return null
            val length = (totalWritten - start).toInt()
            if (length <= 0) return null
            val bytes = read(start, length) ?: return null
            return Segment(start, bytes)
        }
    }

    /** Bytes for a previously recorded span, for REPLAY and CLIP. Null once it has aged out. */
    fun segment(from: Long, length: Int): ByteArray? = read(from, length)

    /** The wall-clock span the window currently covers, or null before anything is buffered. */
    fun timeSpan(): LongRange? {
        synchronized(lock) {
            val first = index.firstOrNull()?.first ?: return null
            val last = index.lastOrNull()?.first ?: return null
            if (last <= first) return null
            return first..last
        }
    }

    /** Byte offset closest to a wall-clock instant — what the timeline scrubber seeks with. */
    fun offsetAtTime(timeMs: Long): Long? {
        synchronized(lock) {
            if (index.isEmpty()) return null
            var best: Pair<Long, Long>? = null
            var bestDelta = Long.MAX_VALUE
            for (sample in index) {
                val delta = kotlin.math.abs(sample.first - timeMs)
                if (delta < bestDelta) {
                    bestDelta = delta
                    best = sample
                }
            }
            return best?.second?.takeIf { it >= oldestRetained }
        }
    }

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

    private fun saveIndexLocked(id: String) {
        try {
            DataOutputStream(indexFile(id).outputStream().buffered()).use { out ->
                out.writeLong(totalWritten)
                out.writeInt(index.size)
                for ((time, offset) in index) {
                    out.writeLong(time)
                    out.writeLong(offset)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "index save failed", t)
        }
    }

    private fun loadIndexLocked(id: String) {
        totalWritten = 0L
        index.clear()
        val file = indexFile(id)
        if (!file.exists()) return
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val total = input.readLong()
                val count = input.readInt()
                if (count !in 0..INDEX_MAX) return
                val restored = ArrayList<Pair<Long, Long>>(count)
                repeat(count) { restored.add(input.readLong() to input.readLong()) }
                totalWritten = total
                index.addAll(restored)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "index load failed", t)
            totalWritten = 0L
            index.clear()
        }
    }

    /** Keep only the few most recently used rings so the cache can't grow without bound. */
    private fun pruneOldRings() {
        val rings = dir.listFiles { f -> f.name.endsWith(".bin") } ?: return
        if (rings.size <= MAX_RINGS) return
        rings.sortedBy { it.lastModified() }
            .dropLast(MAX_RINGS)
            .forEach { ring ->
                ring.delete()
                File(dir, ring.name.removeSuffix(".bin") + ".idx").delete()
            }
    }

    private fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)

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
        const val FLUSH_EVERY = 60
        const val MAX_RINGS = 3
        const val DEFAULT_BPS = 4_000.0 // 32 kbps
    }
}
