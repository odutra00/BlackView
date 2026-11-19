package br.com.blackview.tv.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import br.com.blackview.tv.data.CameraConfig
import br.com.blackview.tv.data.CameraState
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

interface CameraPlayerListener {
    fun onStateChanged(camera: CameraConfig, state: CameraState, message: String? = null)
    fun onAudioStateChanged(camera: CameraConfig, isMuted: Boolean)
}

class CameraPlayer(
    private val libVLC: LibVLC,
    val config: CameraConfig,
    private val listener: CameraPlayerListener? = null
) {
    private val tag = "CameraPlayer-${config.id}"
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isPlaying = false
    private var isMuted = true
    private var reconnectAttempts = 0
    private var isReconnecting = false

    private val reconnectRunnable = Runnable {
        if (!config.enabled) return@Runnable
        Log.d(tag, "Tentando reconectar à câmera ${config.name}...")
        startPlayback()
    }

    private val eventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                Log.d(tag, "Abrindo stream RTSP: ${config.ip}")
                updateState(CameraState.CONNECTING, "Abrindo...")
            }
            MediaPlayer.Event.Buffering -> {
                val percent = event.buffering
                if (!isPlaying) {
                    updateState(CameraState.CONNECTING, "Carregando $percent%")
                }
            }
            MediaPlayer.Event.Playing -> {
                Log.d(tag, "Stream conectado e reproduzindo: ${config.name}")
                isPlaying = true
                isReconnecting = false
                reconnectAttempts = 0
                updateState(CameraState.LIVE, null)
            }
            MediaPlayer.Event.EncounteredError -> {
                Log.w(tag, "Erro ao reproduzir stream da câmera ${config.name}")
                handleDisconnect("Erro na conexão RTSP")
            }
            MediaPlayer.Event.EndReached -> {
                Log.w(tag, "Fim do stream alcançado da câmera ${config.name}")
                handleDisconnect("Conexão encerrada")
            }
            MediaPlayer.Event.Stopped -> {
                isPlaying = false
            }
        }
    }

    fun attachViews(layout: VLCVideoLayout) {
        this.videoLayout = layout
        ensurePlayer().attachViews(layout, null, false, false)
    }

    fun detachViews() {
        mediaPlayer?.detachViews()
        this.videoLayout = null
    }

    private fun ensurePlayer(): MediaPlayer {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer(libVLC).apply {
                setEventListener(eventListener)
                setVolume(if (isMuted) 0 else 100)
            }
        }
        return mediaPlayer!!
    }

    fun start() {
        if (!config.enabled) {
            updateState(CameraState.DISABLED, "Câmera desativada")
            return
        }
        if (config.needsPassword) {
            updateState(CameraState.OFFLINE, "Senha NVR não configurada (Ajustes)")
            return
        }
        startPlayback()
    }

    private fun startPlayback() {
        handler.removeCallbacks(reconnectRunnable)
        val player = ensurePlayer()
        
        try {
            updateState(CameraState.CONNECTING, "Conectando...")
            val media = Media(libVLC, Uri.parse(config.rtspUrl)).apply {
                // Opções para IP Camera / RTSP de baixa latência
                addOption(":network-caching=300")
                addOption(":live-caching=300")
                // Força transporte UDP (necessário para as câmeras Jortan)
                addOption(":rtsp-tcp=0")
                addOption(":rtsp-frame-buffer-size=500000")
                // Aceleração por hardware MediaCodec
                addOption(":codec=mediacodec_ndk,mediacodec_jni,all")
                // Otimizações de renderização contínua
                addOption(":drop-late-frames")
                addOption(":skip-frames")
                addOption(":no-stats")
                addOption(":no-osd")
            }

            player.media = media
            media.release()
            player.play()
        } catch (e: Exception) {
            Log.e(tag, "Falha ao iniciar player", e)
            handleDisconnect(e.localizedMessage ?: "Erro desconhecido")
        }
    }

    fun stop() {
        handler.removeCallbacks(reconnectRunnable)
        isReconnecting = false
        isPlaying = false
        mediaPlayer?.stop()
    }

    fun release() {
        stop()
        mediaPlayer?.let {
            it.setEventListener(null)
            it.detachViews()
            it.release()
        }
        mediaPlayer = null
        videoLayout = null
    }

    fun setMuted(mute: Boolean) {
        isMuted = mute
        mediaPlayer?.setVolume(if (mute) 0 else 100)
        listener?.onAudioStateChanged(config, isMuted)
    }

    fun isMuted(): Boolean = isMuted


    private fun handleDisconnect(reason: String) {
        Log.w(tag, "Câmera desconectada (${config.name}): $reason")
        isPlaying = false
        if (!config.enabled) return

        reconnectAttempts++
        isReconnecting = true
        val delayMs = if (reconnectAttempts <= 3) 3000L else 7000L
        updateState(
            CameraState.RECONNECTING,
            "Reconectando em ${delayMs / 1000}s (tentativa $reconnectAttempts)"
        )

        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun updateState(state: CameraState, message: String?) {
        handler.post {
            listener?.onStateChanged(config, state, message)
        }
    }
}
