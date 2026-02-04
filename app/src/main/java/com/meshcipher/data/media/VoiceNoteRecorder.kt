package com.meshcipher.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records voice notes using the device microphone.
 * Outputs AAC audio in M4A container format.
 */
@Singleton
class VoiceNoteRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTime: Long = 0

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var durationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    data class RecordingResult(
        val file: File,
        val durationMs: Long
    )

    /**
     * Starts recording a voice note.
     */
    fun startRecording(): Result<Unit> {
        if (_isRecording.value) return Result.failure(Exception("Already recording"))

        return try {
            val file = File(getRecordingDir(), "voice_${System.currentTimeMillis()}.m4a")
            currentFile = file

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = mr
            startTime = System.currentTimeMillis()
            _isRecording.value = true
            _durationMs.value = 0

            durationJob = scope.launch {
                while (isActive && _isRecording.value) {
                    _durationMs.value = System.currentTimeMillis() - startTime
                    delay(100)
                }
            }

            Timber.d("Voice recording started: %s", file.absolutePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start voice recording")
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Stops recording and returns the result.
     */
    fun stopRecording(): Result<RecordingResult> {
        if (!_isRecording.value) return Result.failure(Exception("Not recording"))

        return try {
            recorder?.apply {
                stop()
                release()
            }

            val duration = System.currentTimeMillis() - startTime
            val file = currentFile ?: return Result.failure(Exception("No recording file"))

            _isRecording.value = false
            _durationMs.value = duration
            durationJob?.cancel()
            recorder = null

            Timber.d("Voice recording stopped: %d ms, %d bytes", duration, file.length())
            Result.success(RecordingResult(file, duration))
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop voice recording")
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Cancels recording and deletes the file.
     */
    fun cancelRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Timber.w(e, "Error stopping recorder during cancel")
        }
        cleanup()
        currentFile?.delete()
        currentFile = null
    }

    private fun cleanup() {
        recorder = null
        _isRecording.value = false
        _durationMs.value = 0
        durationJob?.cancel()
    }

    private fun getRecordingDir(): File {
        return File(context.filesDir, "voice_notes").also { it.mkdirs() }
    }
}
