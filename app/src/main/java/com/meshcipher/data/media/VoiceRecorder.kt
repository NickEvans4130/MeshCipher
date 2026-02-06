package com.meshcipher.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRecorder @Inject constructor() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var amplitudeJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    fun start(context: Context): File {
        stop()

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.aac")
        outputFile = file

        @Suppress("DEPRECATION")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(AUDIO_BIT_RATE)
            setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        recorder = mediaRecorder
        _isRecording.value = true
        _recordingDurationMs.value = 0L

        val startTime = System.currentTimeMillis()
        amplitudeJob = scope.launch {
            while (_isRecording.value) {
                try {
                    _amplitude.value = mediaRecorder.maxAmplitude
                    _recordingDurationMs.value = System.currentTimeMillis() - startTime
                } catch (e: Exception) {
                    break
                }
                delay(AMPLITUDE_POLL_INTERVAL_MS)
            }
        }

        Timber.d("Voice recording started: %s", file.absolutePath)
        return file
    }

    fun stop(): File? {
        val file = outputFile
        amplitudeJob?.cancel()
        amplitudeJob = null

        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping recorder")
        }

        recorder = null
        _isRecording.value = false
        _amplitude.value = 0
        outputFile = null

        if (file != null) {
            Timber.d("Voice recording stopped: %s (%d bytes)", file.absolutePath, file.length())
        }
        return file
    }

    fun cancel() {
        val file = stop()
        file?.delete()
        Timber.d("Voice recording cancelled")
    }

    companion object {
        private const val AUDIO_BIT_RATE = 64000
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AMPLITUDE_POLL_INTERVAL_MS = 100L
    }
}
