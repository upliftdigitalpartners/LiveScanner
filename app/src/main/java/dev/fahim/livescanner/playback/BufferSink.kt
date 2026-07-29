package dev.fahim.livescanner.playback

import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec

/**
 * Feeds every byte ExoPlayer pulls off the network into the rolling [AudioBuffer].
 *
 * Pairing this with Media3's TeeDataSource means the recorder, live transcription and playback all
 * read the same bytes from one connection — previously transcription opened a second connection to
 * the stream and threw the audio away afterwards.
 */
class BufferSink(private val buffer: AudioBuffer) : DataSink {

    override fun open(dataSpec: DataSpec) {
        buffer.open()
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        this.buffer.write(buffer, offset, length)
    }

    override fun close() {
        // Keep the ring intact across reconnects; the service closes it on release.
    }
}
