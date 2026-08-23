package dev.fahim.livescanner.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The ring is the one place in the app where getting the arithmetic slightly wrong produces audio
 * that is silently corrupt rather than absent, so the wrap and the retention edge are pinned here.
 *
 * A tiny capacity and a zero index interval keep it fast: the same code paths, without writing
 * 16 MB or sleeping for a second.
 */
class AudioBufferTest {

    private fun tempDir(): File = Files.createTempDirectory("ring").toFile()

    private fun buffer(dir: File = tempDir(), capacity: Long = 1_000L) =
        AudioBuffer(dir, capacity = capacity, indexIntervalMs = 0L)

    private fun bytes(value: Byte, count: Int) = ByteArray(count) { value }

    @Test
    fun `bytes come back exactly as written`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        val payload = ByteArray(300) { (it % 251).toByte() }
        buffer.write(payload, 0, payload.size)

        assertEquals(300L, buffer.totalWritten)
        assertArrayEquals(payload, buffer.read(0, 300))
    }

    @Test
    fun `an offset argument is honoured`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        buffer.write(payload, 2, 3) // just 3, 4, 5

        assertEquals(3L, buffer.totalWritten)
        assertArrayEquals(byteArrayOf(3, 4, 5), buffer.read(0, 3))
    }

    @Test
    fun `nothing can be read before anything is written`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        assertNull(buffer.read(0, 10))
        assertNull(buffer.timeSpan())
    }

    @Test
    fun `writing without a feed selected is a no-op rather than a crash`() {
        val buffer = buffer()
        buffer.write(bytes(1, 10), 0, 10)
        assertEquals(0L, buffer.totalWritten)
    }

    @Test
    fun `the window wraps and the oldest bytes age out`() {
        val buffer = buffer(capacity = 1_000L)
        buffer.switchTo("feed")
        buffer.write(bytes(0xA, 600), 0, 600)
        buffer.write(bytes(0xB, 600), 0, 600)

        assertEquals(1_200L, buffer.totalWritten)
        assertEquals(200L, buffer.oldestRetained)

        // Past the trailing edge — gone.
        assertNull("aged-out span must not be served", buffer.read(0, 100))
        // Still inside the window, and still the original content.
        assertArrayEquals(bytes(0xA, 100), buffer.read(200, 100))
        // The newest bytes, which physically live at the start of the file after wrapping.
        assertArrayEquals(bytes(0xB, 100), buffer.read(1_100, 100))
    }

    @Test
    fun `a read that runs past the head is refused`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        buffer.write(bytes(7, 100), 0, 100)
        assertNull(buffer.read(50, 100)) // only 100 bytes exist
    }

    @Test
    fun `a span crossing the wrap point is reassembled in order`() {
        val buffer = buffer(capacity = 1_000L)
        buffer.switchTo("feed")
        buffer.write(bytes(0xA, 900), 0, 900)
        val tail = ByteArray(200) { (it + 1).toByte() }
        buffer.write(tail, 0, tail.size) // occupies logical 900..1100, physically 900..999 then 0..99

        assertArrayEquals(tail, buffer.read(900, 200))
    }

    @Test
    fun `each feed keeps its own window`() {
        val dir = tempDir()
        val buffer = buffer(dir)
        buffer.switchTo("alpha")
        buffer.write(bytes(0xA, 100), 0, 100)
        buffer.switchTo("bravo")
        buffer.write(bytes(0xB, 50), 0, 50)

        assertEquals("bravo's ring is its own", 50L, buffer.totalWritten)
        assertArrayEquals(bytes(0xB, 50), buffer.read(0, 50))

        buffer.switchTo("alpha")
        assertEquals("alpha's window is restored, not restarted", 100L, buffer.totalWritten)
        assertArrayEquals(bytes(0xA, 100), buffer.read(0, 100))
    }

    @Test
    fun `switching to the feed already open changes nothing`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        buffer.write(bytes(1, 100), 0, 100)
        buffer.switchTo("feed")
        assertEquals(100L, buffer.totalWritten)
    }

    @Test
    fun `a window survives being closed and reopened`() {
        val dir = tempDir()
        val first = buffer(dir)
        first.switchTo("feed")
        first.write(bytes(0xC, 400), 0, 400)
        first.close()

        val second = buffer(dir)
        second.switchTo("feed")
        assertEquals("the index should be restored from disk", 400L, second.totalWritten)
        assertArrayEquals(bytes(0xC, 100), second.read(0, 100))
    }

    @Test
    fun `closing resets live state`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        buffer.write(bytes(1, 100), 0, 100)
        buffer.close()
        assertEquals(0L, buffer.totalWritten)
        assertNull(buffer.read(0, 100))
    }

    @Test
    fun `hasHistory reflects whether anything is buffered`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        assertTrue(!buffer.hasHistory)
        buffer.write(bytes(1, 10), 0, 10)
        assertTrue(buffer.hasHistory)
    }

    @Test
    fun `latest returns the most recent audio`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        buffer.write(bytes(0xA, 100), 0, 100)
        buffer.write(bytes(0xB, 100), 0, 100)
        buffer.write(bytes(0xC, 100), 0, 100)

        val segment = buffer.latest(60_000L)
        assertNotNull(segment)
        // The index stamps after each write, so the earliest usable mark is the head of the
        // second chunk — the very first bytes of a stream are never part of a latest() window.
        assertEquals(100L, segment!!.offset)
        assertArrayEquals(bytes(0xB, 100) + bytes(0xC, 100), segment.bytes)
    }

    @Test
    fun `latest is null before anything is buffered`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        assertNull(buffer.latest(60_000L))
    }

    @Test
    fun `the time span covers the buffered window`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        val before = System.currentTimeMillis()
        buffer.write(bytes(1, 100), 0, 100)
        Thread.sleep(5)
        buffer.write(bytes(2, 100), 0, 100)
        val after = System.currentTimeMillis()

        val span = buffer.timeSpan()
        assertNotNull(span)
        assertTrue(span!!.first >= before)
        assertTrue(span.last <= after)
    }

    @Test
    fun `an instant maps to the nearest recorded offset`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        buffer.write(bytes(1, 100), 0, 100)
        Thread.sleep(5)
        buffer.write(bytes(2, 100), 0, 100)

        val span = buffer.timeSpan()!!
        assertEquals(100L, buffer.offsetAtTime(span.first))
        assertEquals(200L, buffer.offsetAtTime(span.last))
        // Far in the past still clamps to something inside the window rather than failing.
        assertNotNull(buffer.offsetAtTime(0L))
    }

    @Test
    fun `offsetAtTime is null before anything is buffered`() {
        val buffer = buffer()
        buffer.switchTo("feed")
        assertNull(buffer.offsetAtTime(System.currentTimeMillis()))
    }

    @Test
    fun `a feed id with path characters cannot escape the directory`() {
        val dir = tempDir()
        val buffer = buffer(dir)
        buffer.switchTo("liveatc:../../etc/passwd")
        buffer.write(bytes(1, 10), 0, 10)

        val strays = dir.listFiles()?.map { it.name }.orEmpty()
        assertTrue("ring files must stay in the directory", strays.isNotEmpty())
        assertTrue(strays.none { it.contains("/") || it.contains("..") })
    }
}
