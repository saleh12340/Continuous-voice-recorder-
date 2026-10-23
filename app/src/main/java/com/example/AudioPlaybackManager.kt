package com.example

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import java.io.File

class AudioPlaybackManager(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    var onPlaybackStateChanged: ((isPlaying: Boolean, currentPosition: Int, duration: Int) -> Unit)? = null
    var onPlaybackCompleted: (() -> Unit)? = null

    fun play(filePath: String, onProgress: (Int, Int) -> Unit) {
        val file = File(filePath)
        if (!file.exists()) return

        stop()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                prepare()
                start()
                setOnCompletionListener {
                    stop()
                    onPlaybackCompleted?.invoke()
                }
            }
            val duration = mediaPlayer?.duration ?: 0
            onPlaybackStateChanged?.invoke(true, 0, duration)
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                onPlaybackStateChanged?.invoke(false, mediaPlayer?.currentPosition ?: 0, mediaPlayer?.duration ?: 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resume() {
        try {
            if (mediaPlayer != null && mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
                onPlaybackStateChanged?.invoke(true, mediaPlayer?.currentPosition ?: 0, mediaPlayer?.duration ?: 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
        onPlaybackStateChanged?.invoke(false, 0, 0)
    }

    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }

    fun getCurrentPosition(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun getDuration(): Int {
        return try {
            mediaPlayer?.duration ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
