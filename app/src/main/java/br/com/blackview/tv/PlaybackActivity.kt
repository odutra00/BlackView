package br.com.blackview.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.com.blackview.tv.data.RecordingManager
import br.com.blackview.tv.data.formatDuration
import br.com.blackview.tv.databinding.ActivityPlaybackBinding
import br.com.blackview.tv.dvr.DvrState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File

class PlaybackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_CAMERA_NAME = "extra_camera_name"
        const val EXTRA_CAMERA_ID = "extra_camera_id"
        const val EXTRA_TIMESTAMP = "extra_timestamp"
        private const val SEEK_STEP_MS = 10000L // 10 segundos
    }

    private val tag = "PlaybackActivity"
    private lateinit var binding: ActivityPlaybackBinding

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isVideoPlaying = false
    private var currentRate = 1.0f
    private val availableRates = floatArrayOf(1.0f, 1.5f, 2.0f, 4.0f, 8.0f)
    private var currentFile: File? = null
    private var cameraId: Int = -1

    private val hideOsdRunnable = Runnable {
        binding.playbackOsd.animate().alpha(0f).setDuration(400).withEndAction {
            binding.playbackOsd.visibility = View.GONE
        }.start()
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                val time = player.time
                val length = player.length
                if (length > 0) {
                    val progress = ((time.toDouble() / length.toDouble()) * 1000).toInt()
                    binding.playbackSeekBar.progress = progress
                    binding.tvTimeCurrent.text = formatTime(time)
                    binding.tvTimeTotal.text = formatTime(length)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run {
            finish()
            return
        }
        val cameraName = intent.getStringExtra(EXTRA_CAMERA_NAME) ?: "Gravação"
        val timestamp = intent.getStringExtra(EXTRA_TIMESTAMP) ?: ""
        cameraId = intent.getIntExtra(EXTRA_CAMERA_ID, -1)

        binding.tvPlaybackTitle.text = cameraName
        binding.tvPlaybackSubtitle.text = "Gravado em: $timestamp"

        initVlc()
        setupSeekBar()
        startFilePlayback(filePath)
        showOsd()
    }

    private fun initVlc() {
        val options = arrayListOf(
            "--no-stats",
            "--no-osd"
        )
        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC).apply {
            setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Buffering -> {
                        if (event.buffering < 100f) {
                            binding.playbackProgress.visibility = View.VISIBLE
                        } else {
                            binding.playbackProgress.visibility = View.GONE
                        }
                    }
                    MediaPlayer.Event.Playing -> {
                        isVideoPlaying = true
                        binding.playbackProgress.visibility = View.GONE
                    }
                    MediaPlayer.Event.Paused -> {
                        isVideoPlaying = false
                    }
                    MediaPlayer.Event.Stopped -> {
                        isVideoPlaying = false
                    }
                    MediaPlayer.Event.EndReached -> {
                        Log.i(tag, "Fim do arquivo; buscando próximo segmento")
                        playNextSegment()
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        Log.e(tag, "Erro na reprodução do arquivo de vídeo")
                        finish()
                    }
                }
            }
        }
        mediaPlayer?.attachViews(binding.playbackVideoLayout, null, false, false)
    }

    private fun setupSeekBar() {
        binding.playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let { player ->
                        val length = player.length
                        if (length > 0) {
                            val targetTime = (progress.toDouble() / 1000.0 * length).toLong()
                            player.time = targetTime
                            binding.tvTimeCurrent.text = formatTime(targetTime)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                showOsd()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                showOsd()
            }
        })
    }

    private fun startFilePlayback(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(tag, "Arquivo não encontrado: $filePath")
            finish()
            return
        }

        currentFile = file
        try {
            val media = Media(libVLC, Uri.fromFile(file)).apply {
                addOption(":codec=mediacodec_ndk,mediacodec_jni,all")
                addOption(":no-stats")
                addOption(":no-osd")
            }
            mediaPlayer?.media = media
            media.release()
            mediaPlayer?.play()
            mediaPlayer?.rate = currentRate
            handler.removeCallbacks(updateProgressRunnable)
            handler.post(updateProgressRunnable)
        } catch (e: Exception) {
            Log.e(tag, "Falha ao iniciar reprodução de arquivo", e)
            finish()
        }
    }

    /** Reprodução contínua: ao terminar um segmento, abre o seguinte da mesma câmera. */
    private fun playNextSegment() {
        val file = currentFile
        if (file == null || cameraId < 0) {
            finish()
            return
        }
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) {
                RecordingManager(this@PlaybackActivity)
                    .nextRecording(file, cameraId, DvrState.status.value.activePaths)
            }
            if (next == null || isFinishing) {
                finish()
                return@launch
            }
            binding.tvPlaybackSubtitle.text = "Gravado em: ${next.formattedDate}"
            showOsd()
            mediaPlayer?.stop()
            startFilePlayback(next.file.absolutePath)
        }
    }

    private fun togglePlayPause() {
        showOsd()
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                isVideoPlaying = false
                showCenterStatus(false)
            } else {
                player.play()
                isVideoPlaying = true
                showCenterStatus(true)
            }
        }
    }

    private fun seekRelative(offsetMs: Long) {
        showOsd()
        mediaPlayer?.let { player ->
            val newTime = (player.time + offsetMs).coerceIn(0L, player.length)
            player.time = newTime
            binding.tvTimeCurrent.text = formatTime(newTime)
        }
    }

    private fun cycleSpeed() {
        showOsd()
        val currentIndex = availableRates.indexOfFirst { it == currentRate }.takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + 1) % availableRates.size
        currentRate = availableRates[nextIndex]
        mediaPlayer?.rate = currentRate
        binding.tvSpeedBadge.text = "${currentRate}x"
    }

    private fun resetSpeed() {
        showOsd()
        currentRate = 1.0f
        mediaPlayer?.rate = currentRate
        binding.tvSpeedBadge.text = "1.0x"
    }

    private fun showCenterStatus(isPlay: Boolean) {
        binding.ivCenterStatus.visibility = View.VISIBLE
        binding.ivCenterStatus.alpha = 1f
        binding.ivCenterStatus.animate().alpha(0f).setDuration(800).withEndAction {
            binding.ivCenterStatus.visibility = View.GONE
        }.start()
    }

    private fun showOsd() {
        handler.removeCallbacks(hideOsdRunnable)
        binding.playbackOsd.alpha = 1f
        binding.playbackOsd.visibility = View.VISIBLE
        handler.postDelayed(hideOsdRunnable, 4000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        showOsd()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekRelative(-SEEK_STEP_MS)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekRelative(SEEK_STEP_MS)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                cycleSpeed()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                resetSpeed()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun formatTime(millis: Long): String = if (millis < 1000) "00:00" else formatDuration(millis)

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideOsdRunnable)
        mediaPlayer?.let {
            it.setEventListener(null)
            it.stop()
            it.detachViews()
            it.release()
        }
        libVLC?.release()
    }
}
