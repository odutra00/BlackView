package br.com.blackview.tv.dvr

import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import br.com.blackview.tv.data.CameraConfig
import br.com.blackview.tv.data.RecordingManager
import br.com.blackview.tv.data.StorageLocation
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.util.Calendar

/**
 * Grava continuamente o stream RTSP de uma câmera em arquivos segmentados.
 *
 * - Usa stream output do VLC (`#std{access=file,mux=avi}`): o vídeo H.265 e o áudio
 *   G.711 A-law são copiados sem decodificação (custo de CPU mínimo, sem usar o
 *   decodificador de hardware da TV). AVI é o contêiner que o próprio VLC Android
 *   escolhe para esse par de codecs (TS não aceita A-law).
 * - Abre uma sessão RTSP própria. As câmeras Jortan/GWIPC aceitam no máximo 2
 *   sessões simultâneas: mosaico/tela cheia (1) + DVR (1).
 * - Troca de arquivo alinhada ao relógio (ex.: 30 min → :00 e :30). Na troca há
 *   uma lacuna de ~1–3 s enquanto a sessão RTSP é reaberta.
 * - Watchdog: se o arquivo parar de crescer, a sessão é reiniciada.
 *
 * Todas as operações do MediaPlayer rodam em uma HandlerThread própria, pois
 * stop() do LibVLC pode bloquear.
 */
class SegmentRecorder(
    private val libVLC: LibVLC,
    val camera: CameraConfig,
    private val location: StorageLocation,
    private val recordingManager: RecordingManager,
    private val segmentMinutes: Int,
    /** Chamado antes de abrir cada novo arquivo (retenção de espaço). */
    private val beforeNewSegment: () -> Unit
) {
    private val tag = "DvrRec-${camera.id}"
    private val thread = HandlerThread("dvr-cam-${camera.id}").apply { start() }
    private val handler = Handler(thread.looper)

    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var lastSize = -1L
    private var lastGrowthAt = 0L
    private var retryCount = 0
    private var running = false

    private val rolloverRunnable = Runnable { restart("troca de segmento", 0) }
    private val retryRunnable = Runnable { openSegment() }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val f = currentFile
            val now = System.currentTimeMillis()
            if (f != null) {
                val size = if (f.exists()) f.length() else 0L
                if (size > lastSize) {
                    lastSize = size
                    lastGrowthAt = now
                    if (size > 64 * 1024) {
                        if (retryCount != 0) retryCount = 0
                        publish(RecorderStatus.RECORDING, null)
                    }
                } else if (now - lastGrowthAt > if (lastSize < 64 * 1024) STARTUP_TIMEOUT_MS else STALL_TIMEOUT_MS) {
                    Log.w(tag, "Arquivo parou de crescer há ${(now - lastGrowthAt) / 1000}s; reiniciando")
                    restart("sem dados da câmera", nextRetryDelay())
                    return
                }
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val eventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.EncounteredError -> handler.post {
                if (running) restart("erro RTSP", nextRetryDelay())
            }
            MediaPlayer.Event.EndReached -> handler.post {
                if (running) restart("stream encerrado", nextRetryDelay())
            }
        }
    }

    fun start() = handler.post {
        if (running) return@post
        running = true
        openSegment()
    }

    /** Para a gravação e libera recursos. Bloqueia até finalizar o arquivo (máx. ~5 s). */
    fun stopAndRelease() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            running = false
            handler.removeCallbacksAndMessages(null)
            closeSegment()
            player?.let {
                it.setEventListener(null)
                it.release()
            }
            player = null
            publish(RecorderStatus.STOPPED, null)
            done.countDown()
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun openSegment() {
        if (!running) return
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(rolloverRunnable)

        try {
            beforeNewSegment()
        } catch (e: Exception) {
            Log.w(tag, "Falha na retenção antes do segmento", e)
        }

        if (!location.appDir.exists() || location.freeBytes < MIN_FREE_TO_START) {
            Log.w(tag, "Destino indisponível ou sem espaço: ${location.label}")
            publish(RecorderStatus.RETRYING, "Destino sem espaço/indisponível")
            handler.postDelayed(retryRunnable, 60_000)
            return
        }

        val now = System.currentTimeMillis()
        val file = recordingManager.newSegmentFile(location, camera.id, now)
        currentFile = file
        lastSize = -1L
        lastGrowthAt = now
        publish(RecorderStatus.STARTING, null)

        val mp = player ?: MediaPlayer(libVLC).also {
            it.setEventListener(eventListener)
            player = it
        }

        try {
            val media = Media(libVLC, Uri.parse(camera.rtspUrl)).apply {
                addOption(":network-caching=1000")
                addOption(":rtsp-tcp=0") // câmeras Jortan: somente UDP funciona
                addOption(":rtsp-frame-buffer-size=500000")
                addOption(":sout=#std{access=file,mux=avi,dst='${file.absolutePath}'}")
                addOption(":sout-all")
                // O áudio chega antes do vídeo (o packetizer HEVC/H.264 só cria o ES após
                // receber VPS/SPS/PPS, que só vêm no fluxo junto aos quadros-chave: GOP de
                // ~9 s e perdas UDP → observado até 23 s). O AVI não aceita novos streams
                // depois de iniciado, então o mux espera 45 s antes de começar a escrever
                // (nada é perdido: os pacotes ficam em memória, ~7 MB). A opção
                // --sout-mux-caching é global e está na instância LibVLC do DvrService.
            }
            mp.media = media
            media.release()
            mp.play()
            Log.i(tag, "Gravando ${camera.name} → ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Falha ao iniciar gravação", e)
            restart("falha ao iniciar", nextRetryDelay())
            return
        }

        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        handler.postDelayed(rolloverRunnable, msUntilNextBoundary(now))
    }

    private fun closeSegment() {
        try {
            player?.stop() // finaliza o AVI (índice/cabeçalho)
        } catch (e: Exception) {
            Log.w(tag, "Erro ao parar player", e)
        }
        currentFile?.let { f ->
            // Remove arquivos vazios de tentativas de conexão que falharam
            if (f.exists() && f.length() < 32 * 1024) f.delete()
        }
        currentFile = null
    }

    private fun restart(reason: String, delayMs: Long) {
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(rolloverRunnable)
        handler.removeCallbacks(retryRunnable)
        closeSegment()
        if (!running) return
        if (delayMs > 0) {
            Log.w(tag, "Reiniciando em ${delayMs / 1000}s: $reason")
            publish(RecorderStatus.RETRYING, "$reason — nova tentativa em ${delayMs / 1000}s")
            handler.postDelayed(retryRunnable, delayMs)
        } else {
            openSegment()
        }
    }

    private fun nextRetryDelay(): Long {
        retryCount++
        return when {
            retryCount <= 3 -> 5_000L
            retryCount <= 10 -> 15_000L
            else -> 60_000L
        }
    }

    private fun msUntilNextBoundary(now: Long): Long {
        val seg = segmentMinutes.coerceIn(1, 120)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val nextBoundaryMin = (minutesOfDay / seg + 1) * seg
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, nextBoundaryMin)
        var delta = cal.timeInMillis - now
        // Evita segmentos minúsculos quando a gravação começa perto da fronteira
        if (delta < 60_000L) delta += seg * 60_000L
        return delta
    }

    private fun publish(status: RecorderStatus, message: String?) {
        DvrState.updateCamera(
            CameraRecStatus(
                cameraId = camera.id,
                status = status,
                currentFile = if (status == RecorderStatus.STOPPED) null else currentFile?.absolutePath,
                message = message
            )
        )
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val STALL_TIMEOUT_MS = 30_000L
        const val MUX_CACHING_MS = 45_000L
        /** Na partida o arquivo só cresce após a espera do mux. */
        private const val STARTUP_TIMEOUT_MS = MUX_CACHING_MS + 45_000L
        private const val MIN_FREE_TO_START = 300L * 1024 * 1024
    }
}
