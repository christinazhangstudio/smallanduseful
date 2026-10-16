package dev.lystic.fanyixia

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

internal data class CapturedAudio(val samples: FloatArray, val count: Int)

internal class Microphone {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var samples = FloatArray(0)
    private var count = 0
    @Volatile private var failure: String? = null

    @SuppressLint("MissingPermission") // MainActivity requests RECORD_AUDIO before this call.
    fun start(onFinished: () -> Unit) {
        check(record == null) { "Microphone is already in use." }
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "This microphone does not support 16 kHz mono recording." }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 6400)).build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize." }
            samples = FloatArray(16000 * 60)
            count = 0
            failure = null
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start." }
            record = recorder
            running = true
            thread = Thread({
                try {
                    val buffer = ShortArray(1600)
                    while (running && count < samples.size) {
                        val read = recorder.read(buffer, 0, minOf(buffer.size, samples.size - count), AudioRecord.READ_BLOCKING)
                        if (read < 0) {
                            if (running) failure = "Microphone read failed ($read). Retry recording."
                            break
                        }
                        if (read == 0) continue
                        for (index in 0 until read) samples[count + index] = buffer[index] / 32768f
                        count += read
                    }
                } catch (error: Exception) {
                    if (running) failure = error.message ?: "Microphone interrupted."
                } finally {
                    running = false
                    runCatching { recorder.stop() }
                    recorder.release()
                    onFinished()
                }
            }, "mandarin-capture").also { it.start() }
        } catch (error: Exception) {
            recorder.release()
            record = null
            throw error
        }
    }

    // stop() unblocks READ_BLOCKING. Join ensures AudioRecord is released before returning.
    fun stop(): CapturedAudio {
        running = false
        runCatching { record?.stop() }
        thread?.join()
        record = null
        thread = null
        val result = CapturedAudio(samples, count)
        samples = FloatArray(0)
        count = 0
        failure?.let { error(it) }
        return result
    }
}
