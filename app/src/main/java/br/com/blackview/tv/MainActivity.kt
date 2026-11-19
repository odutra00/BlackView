package br.com.blackview.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import br.com.blackview.tv.data.CameraConfig
import br.com.blackview.tv.data.CameraRepository
import br.com.blackview.tv.data.CameraState
import br.com.blackview.tv.data.DvrSettings
import br.com.blackview.tv.data.formatGb
import br.com.blackview.tv.databinding.ActivityMainBinding
import br.com.blackview.tv.databinding.ViewInfoTileBinding
import br.com.blackview.tv.dvr.DvrService
import br.com.blackview.tv.dvr.DvrState
import br.com.blackview.tv.dvr.DvrStatus
import br.com.blackview.tv.player.CameraPlayer
import br.com.blackview.tv.player.CameraPlayerListener
import br.com.blackview.tv.ui.CameraTileView
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), CameraPlayerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: CameraRepository
    private lateinit var dvrSettings: DvrSettings
    private var loadedConfigVersion = 0L
    private var passwordPromptShown = false
    private val liveStates = mutableMapOf<Int, CameraState>()
    private var libVLC: LibVLC? = null

    private val cameraPlayers = mutableListOf<CameraPlayer>()
    private val cameraTiles = mutableListOf<CameraTileView>()

    private var fullscreenPlayer: CameraPlayer? = null
    private var isFullscreen = false

    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))

    private var infoBinding: ViewInfoTileBinding? = null

    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            val timeStr = clockFormat.format(now)
            val dateStr = dateFormat.format(now).replaceFirstChar { it.uppercase() }

            binding.tvHeaderClock.text = timeStr
            infoBinding?.tvClockTime?.text = timeStr
            infoBinding?.tvClockDate?.text = dateStr

            handler.postDelayed(this, 1000)
        }
    }

    private val hideOsdRunnable = Runnable {
        binding.fullscreenOsd.animate().alpha(0f).setDuration(400).withEndAction {
            binding.fullscreenOsd.visibility = View.GONE
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = CameraRepository(this)
        dvrSettings = DvrSettings(this)
        loadedConfigVersion = repository.configVersion

        initVlc()
        setupTiles()
        setupFullscreenListeners()
        observeDvr()

        handler.post(clockRunnable)

        // Garante o DVR rodando (após atualização do app, crash, etc.)
        DvrService.sync(this)
    }

    private fun observeDvr() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DvrState.status.collect { renderDvrStatus(it) }
            }
        }
    }

    private fun renderDvrStatus(s: DvrStatus) {
        cameraTiles.forEach { it.setRecording(s.isRecording(it.config.id)) }
        fullscreenPlayer?.let { p ->
            binding.tvFullscreenRecBadge.visibility =
                if (s.isRecording(p.config.id)) View.VISIBLE else View.GONE
        }
        infoBinding?.tvNetworkInfo?.text = when {
            !dvrSettings.enabled -> "DVR desligado  •  segure OK para Ajustes"
            !s.serviceRunning -> "DVR iniciando…"
            !s.storageAvailable -> "DVR: ${s.message ?: "destino indisponível"}"
            else -> "● DVR gravando ${s.recordingCount} câm • ${s.storageLabel} • ${formatGb(s.freeBytes)} livres"
        }
    }

    private fun openSettings(focusCameras: Boolean = false) {
        startActivity(Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_FOCUS_CAMERAS, focusCameras))
    }

    private fun initVlc() {
        val options = arrayListOf(
            "--no-stats",
            "--no-osd"
        )
        libVLC = LibVLC(this, options)
    }

    private fun setupTiles() {
        val cameras = repository.getCameras()
        val slots = listOf(
            binding.slot0,
            binding.slot1,
            binding.slot2,
            binding.slot3,
            binding.slot4
        )

        cameras.forEachIndexed { index, config ->
            if (index < slots.size) {
                val tile = CameraTileView(this, config) { clickedCamera ->
                    enterFullscreen(clickedCamera)
                }

                val player = CameraPlayer(libVLC!!, config, object : CameraPlayerListener {
                    override fun onStateChanged(
                        camera: CameraConfig,
                        state: CameraState,
                        message: String?
                    ) {
                        tile.onStateChanged(camera, state, message)
                        this@MainActivity.onStateChanged(camera, state, message)
                    }

                    override fun onAudioStateChanged(camera: CameraConfig, isMuted: Boolean) {
                        tile.onAudioStateChanged(camera, isMuted)
                        this@MainActivity.onAudioStateChanged(camera, isMuted)
                    }

                })

                cameraPlayers.add(player)
                cameraTiles.add(tile)

                slots[index].addView(tile)
                tile.bindPlayer(player)
            }
        }

        // Slot 5: Info & Clock tile com atalho para Gravações
        infoBinding = ViewInfoTileBinding.inflate(layoutInflater, binding.slot5, true)
        val openRecordings = {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }
        infoBinding?.tileInfoRoot?.setOnClickListener { openRecordings() }
        infoBinding?.tileInfoRoot?.setOnLongClickListener { openSettings(); true }
        infoBinding?.btnOpenRecordings?.setOnClickListener { openRecordings() }

        // Pre-focus the first active camera
        binding.slot0.post {
            cameraTiles.firstOrNull()?.requestFocus()
        }
    }

    private fun setupFullscreenListeners() {
        binding.layoutFullscreen.setOnClickListener {
            toggleFullscreenOsd()
        }
    }

    private fun enterFullscreen(camera: CameraConfig) {
        val player = cameraPlayers.firstOrNull { it.config.id == camera.id } ?: return
        val tile = cameraTiles.firstOrNull { it.config.id == camera.id } ?: return

        fullscreenPlayer = player
        isFullscreen = true

        // Ensure black scrim is visible to prevent showing stale frame of previously viewed camera
        binding.fullscreenBlackScrim.alpha = 1f
        binding.fullscreenBlackScrim.visibility = View.VISIBLE

        // Move video layout rendering to fullscreen
        tile.unbindPlayer()
        player.attachViews(binding.fullscreenVideoLayout)

        // Unmute audio for the camera in fullscreen mode
        player.setMuted(false)

        binding.tvFullscreenTitle.text = camera.name
        binding.tvFullscreenSubtitle.text =
            "${camera.ip} • H.265 (UDP) • Áudio Ativo"
        binding.tvFullscreenRecBadge.visibility =
            if (DvrState.status.value.isRecording(camera.id)) View.VISIBLE else View.GONE

        binding.layoutMosaic.visibility = View.GONE
        binding.layoutFullscreen.visibility = View.VISIBLE
        binding.layoutFullscreen.requestFocus()

        showFullscreenOsd()

        // Hide black scrim as soon as layout settles
        binding.fullscreenBlackScrim.postDelayed({
            binding.fullscreenBlackScrim.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction { binding.fullscreenBlackScrim.visibility = View.GONE }
                .start()
        }, 150)
    }

    private fun exitFullscreen() {
        val player = fullscreenPlayer ?: return
        val tile = cameraTiles.firstOrNull { it.config.id == player.config.id } ?: return

        handler.removeCallbacks(hideOsdRunnable)

        // Show black scrim immediately on exit
        binding.fullscreenBlackScrim.alpha = 1f
        binding.fullscreenBlackScrim.visibility = View.VISIBLE

        // Mute audio and re-attach back to tile view
        player.setMuted(true)
        player.detachViews()
        tile.bindPlayer(player)

        fullscreenPlayer = null
        isFullscreen = false

        binding.layoutFullscreen.visibility = View.GONE
        binding.layoutMosaic.visibility = View.VISIBLE

        tile.requestFocus()
    }

    private fun showFullscreenOsd() {
        handler.removeCallbacks(hideOsdRunnable)
        binding.fullscreenOsd.alpha = 1f
        binding.fullscreenOsd.visibility = View.VISIBLE
        handler.postDelayed(hideOsdRunnable, 3500)
    }

    private fun toggleFullscreenOsd() {
        if (binding.fullscreenOsd.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideOsdRunnable)
            binding.fullscreenOsd.visibility = View.GONE
        } else {
            showFullscreenOsd()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (isFullscreen) {
                        exitFullscreen()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MUTE -> {
                    toggleFocusedTileAudio()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_RECORD,
                KeyEvent.KEYCODE_PROG_RED -> {
                    toggleDvrForCamera()
                    return true
                }
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_PROG_BLUE -> {
                    openSettings()
                    return true
                }
                KeyEvent.KEYCODE_PROG_YELLOW,
                KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_DVR -> {
                    startActivity(Intent(this, RecordingsActivity::class.java))
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Liga/desliga a gravação contínua (DVR) da câmera em foco ou em tela cheia. */
    private fun toggleDvrForCamera() {
        val target = if (isFullscreen) fullscreenPlayer?.config
        else cameraTiles.firstOrNull { it.hasFocus() }?.config

        if (target == null || !target.enabled) {
            Toast.makeText(this, "Selecione uma câmera ativa", Toast.LENGTH_SHORT).show()
            return
        }
        if (!dvrSettings.enabled) {
            Toast.makeText(this, "DVR desligado: ative a gravação contínua em Ajustes", Toast.LENGTH_LONG).show()
            openSettings()
            return
        }
        val cameras = repository.getCameras()
        val nowRecording = dvrSettings.shouldRecord(target)
        dvrSettings.setRecordCamera(target.id, !nowRecording, cameras)
        DvrService.sync(this)
        Toast.makeText(
            this,
            if (nowRecording) "DVR: ${target.name} não será mais gravada" else "DVR: gravando ${target.name}",
            Toast.LENGTH_SHORT
        ).show()
        if (isFullscreen) showFullscreenOsd()
    }

    private fun toggleFocusedTileAudio() {
        if (isFullscreen) {
            fullscreenPlayer?.let { p ->
                val newMuted = !p.isMuted()
                p.setMuted(newMuted)
                showFullscreenOsd()
            }
        } else {
            val focusedTile = cameraTiles.firstOrNull { it.hasFocus() }
            focusedTile?.player?.let { p ->
                val newMuted = !p.isMuted()
                // Mute all other players
                cameraPlayers.forEach { it.setMuted(true) }
                p.setMuted(newMuted)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Configuração alterada em Ajustes: recria o mosaico com os novos IPs/senhas
        if (repository.configVersion != loadedConfigVersion) {
            recreate()
            return
        }
        cameraPlayers.forEach { it.start() }

        if (!passwordPromptShown && repository.getCameras().any { it.needsPassword }) {
            passwordPromptShown = true
            openSettings(focusCameras = true)
        }
    }

    override fun onStop() {
        super.onStop()
        cameraPlayers.forEach { it.stop() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(hideOsdRunnable)
        cameraPlayers.forEach { it.release() }
        cameraPlayers.clear()
        libVLC?.release()
        libVLC = null
    }

    override fun onStateChanged(camera: CameraConfig, state: CameraState, message: String?) {
        liveStates[camera.id] = state
        updateOnlineCount()
    }

    override fun onAudioStateChanged(camera: CameraConfig, isMuted: Boolean) {
        if (isFullscreen && fullscreenPlayer?.config?.id == camera.id) {
            binding.tvFullscreenSubtitle.text =
                "${camera.ip} • H.265 (UDP) • ${if (isMuted) "Áudio Mudo" else "Áudio Ativo"}"
        }
    }

    private fun updateOnlineCount() {
        val totalEnabled = cameraPlayers.count { it.config.enabled }
        val live = liveStates.count { it.value == CameraState.LIVE }
        infoBinding?.tvOnlineCameras?.text = "● $live/$totalEnabled Câmeras Ao Vivo"
    }
}
