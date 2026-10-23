package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.FileInputStream

class AudioPlaybackManager(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    var onPlaybackStateChanged: ((isPlaying: Boolean, currentPosition: Int, duration: Int) -> Unit)? = null
    var onPlaybackCompleted: (() -> Unit)? = null

    fun play(filePath: String, onProgress: (Int, Int) -> Unit) {
        val file = File(filePath)
        if (!file.isFile || !file.canRead() || file.length() < 256L) {
            Log.w("AudioPlaybackManager", "File is missing, unreadable, or empty: $filePath")
            onPlaybackStateChanged?.invoke(false, 0, 0)
            return
        }
        stop()

        var player: MediaPlayer? = null
        try {
            player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            // Using FileInputStream file descriptor is the most reliable way to play local files across all Android versions
            val fis = FileInputStream(file)
            player.setDataSource(fis.fd)
            fis.close()

            player.setOnPreparedListener { prepared ->
                try {
                    prepared.start()
                    onPlaybackStateChanged?.invoke(true, 0, prepared.duration)
                    onProgress(0, prepared.duration)
                } catch (e: Exception) {
                    Log.e("AudioPlaybackManager", "Start after prepared failed", e)
                }
            }
            player.setOnCompletionListener {
                releasePlayer()
                onPlaybackCompleted?.invoke()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e("AudioPlaybackManager", "Playback error what=$what extra=$extra")
                releasePlayer()
                onPlaybackStateChanged?.invoke(false, 0, 0)
                true
            }
            mediaPlayer = player
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e("AudioPlaybackManager", "Could not play $filePath", e)
            try { player?.release() } catch (_: Exception) {}
            mediaPlayer = null
            onPlaybackStateChanged?.invoke(false, 0, 0)
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let { if (it.isPlaying) it.pause() }
            emitState()
        } catch (e: Exception) {
            Log.w("AudioPlaybackManager", "Pause failed", e)
        }
    }

    fun resume() {
        try {
            mediaPlayer?.let { if (!it.isPlaying) it.start() }
            emitState()
        } catch (e: Exception) {
            Log.w("AudioPlaybackManager", "Resume failed", e)
        }
    }

    fun seekTo(positionMs: Int) {
        try { mediaPlayer?.seekTo(positionMs.coerceAtLeast(0)) }
        catch (e: Exception) { Log.w("AudioPlaybackManager", "Seek failed", e) }
    }

    fun stop() {
        releasePlayer()
        onPlaybackStateChanged?.invoke(false, 0, 0)
    }

    private fun releasePlayer() {
        val old = mediaPlayer
        mediaPlayer = null
        try {
            old?.setOnPreparedListener(null)
            old?.setOnCompletionListener(null)
            old?.setOnErrorListener(null)
        } catch (_: Exception) {}
        try {
            if (old?.isPlaying == true) {
                old.stop()
            }
            old?.release()
        } catch (_: Exception) {}
    }

    private fun emitState() {
        val p = mediaPlayer
        val playing = try { p?.isPlaying == true } catch (_: Exception) { false }
        val position = try { p?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        val duration = try { p?.duration ?: 0 } catch (_: Exception) { 0 }
        onPlaybackStateChanged?.invoke(playing, position, duration)
    }

    fun isPlaying(): Boolean = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
    fun getCurrentPosition(): Int = try { mediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }
    fun getDuration(): Int = try { mediaPlayer?.duration ?: 0 } catch (_: Exception) { 0 }
}
