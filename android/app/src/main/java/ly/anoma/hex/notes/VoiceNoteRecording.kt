package ly.anoma.hex.notes

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.anoma.hex.speech.DICTATION_SAMPLE_RATE

private const val WAV_HEADER_BYTES = 44

internal fun voiceNoteDurationMillis(wavBytes: Int): Int =
    ((wavBytes - WAV_HEADER_BYTES).coerceAtLeast(0) * 1000L / (DICTATION_SAMPLE_RATE * Short.SIZE_BYTES)).toInt()

/** Preserve the mic's mono PCM16 samples in a WAV Android can play. */
internal fun voiceNoteWav(samples: ShortArray): ByteArray {
    val pcmBytes = samples.size * Short.SIZE_BYTES
    val wav = ByteBuffer.allocate(WAV_HEADER_BYTES + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
    wav.put("RIFF".toByteArray(Charsets.US_ASCII))
    wav.putInt(WAV_HEADER_BYTES - 8 + pcmBytes)
    wav.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    wav.putInt(16)
    wav.putShort(1) // PCM
    wav.putShort(1) // Mono
    wav.putInt(DICTATION_SAMPLE_RATE)
    wav.putInt(DICTATION_SAMPLE_RATE * Short.SIZE_BYTES)
    wav.putShort(Short.SIZE_BYTES.toShort())
    wav.putShort(Short.SIZE_BITS.toShort())
    wav.put("data".toByteArray(Charsets.US_ASCII))
    wav.putInt(pcmBytes)
    wav.asShortBuffer().put(samples)
    return wav.array()
}

private class RecordingSource(private val wav: ByteArray) : MediaDataSource() {
    override fun getSize(): Long = wav.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= wav.size) return -1
        val start = position.toInt()
        val count = minOf(size, wav.size - start)
        wav.copyInto(buffer, offset, start, start + count)
        return count
    }

    override fun close() = Unit
}

/** Playback belongs to the open note and stops when that screen goes away. */
internal class VoiceNotePlayback(context: Context, private val scope: CoroutineScope, private val store: VoiceNoteStore) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var started = false
    var current by mutableStateOf<Long?>(null)
        private set
    var failed by mutableStateOf<Long?>(null)
        private set

    fun toggle(id: Long) {
        val wasPlaying = current == id
        stop()
        if (wasPlaying) return
        val candidate = try {
            MediaPlayer()
        } catch (_: RuntimeException) {
            failed = id
            return
        }
        current = id
        player = candidate
        candidate.setAudioAttributes(attributes)
        candidate.setOnPreparedListener { start(it) }
        candidate.setOnCompletionListener { if (it === player) stop() }
        candidate.setOnErrorListener { media, _, _ -> fail(media); true }
        scope.launch {
            val wav = withContext(Dispatchers.IO) { runCatching { store.recording(id) }.getOrNull() }
            if (candidate !== player) return@launch
            if (wav == null) {
                fail(candidate)
                return@launch
            }
            try {
                candidate.setDataSource(RecordingSource(wav))
                candidate.prepareAsync()
            } catch (_: Exception) {
                fail(candidate)
            }
        }
    }

    private fun start(candidate: MediaPlayer) {
        if (candidate !== player) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { if (it < 0 && candidate === player) stop() }
            .build()
        if (audio?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(candidate)
            return
        }
        focus = request
        try {
            candidate.start()
            started = true
        } catch (_: IllegalStateException) {
            fail(candidate)
        }
    }

    private fun fail(candidate: MediaPlayer) {
        if (candidate !== player) return
        val id = current
        stop()
        failed = id
    }

    fun positionMillis(id: Long): Int {
        val active = player ?: return 0
        return if (started && current == id) runCatching { active.currentPosition }.getOrDefault(0) else 0
    }

    fun stop() {
        val previous = player
        player = null
        started = false
        current = null
        failed = null
        previous?.release()
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
    }
}
