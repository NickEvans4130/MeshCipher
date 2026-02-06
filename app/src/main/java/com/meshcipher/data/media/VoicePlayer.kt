package com.meshcipher.data.media

import android.media.MediaPlayer
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoicePlayer @Inject constructor() {

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var currentPlayingId: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playingMediaId = MutableStateFlow<String?>(null)
    val playingMediaId: StateFlow<String?> = _playingMediaId.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    fun play(filePath: String, mediaId: String) {
        if (currentPlayingId == mediaId && mediaPlayer?.isPlaying == true) {
            pause()
            return
        }

        stop()

        try {
            val player = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
            }

            player.setOnCompletionListener {
                stop()
            }

            mediaPlayer = player
            currentPlayingId = mediaId
            _isPlaying.value = true
            _playingMediaId.value = mediaId

            progressJob = scope.launch {
                while (_isPlaying.value) {
                    try {
                        val current = player.currentPosition.toFloat()
                        val total = player.duration.toFloat()
                        _playbackProgress.value = if (total > 0) current / total else 0f
                    } catch (e: Exception) {
                        break
                    }
                    delay(PROGRESS_UPDATE_INTERVAL_MS)
                }
            }

            Timber.d("Playing voice: %s", filePath)
        } catch (e: Exception) {
            Timber.e(e, "Failed to play voice: %s", filePath)
            stop()
        }
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
            _isPlaying.value = false
            progressJob?.cancel()
        } catch (e: Exception) {
            Timber.w(e, "Error pausing playback")
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping playback")
        }

        mediaPlayer = null
        currentPlayingId = null
        _isPlaying.value = false
        _playingMediaId.value = null
        _playbackProgress.value = 0f
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS = 100L
    }
}
