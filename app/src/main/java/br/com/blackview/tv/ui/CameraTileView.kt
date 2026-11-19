package br.com.blackview.tv.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import br.com.blackview.tv.R
import br.com.blackview.tv.data.CameraConfig
import br.com.blackview.tv.data.CameraState
import br.com.blackview.tv.databinding.ViewCameraTileBinding
import br.com.blackview.tv.player.CameraPlayer
import br.com.blackview.tv.player.CameraPlayerListener
import org.videolan.libvlc.util.VLCVideoLayout

class CameraTileView(
    context: Context,
    val config: CameraConfig,
    private val onTileClicked: (CameraConfig) -> Unit
) : FrameLayout(context), CameraPlayerListener {

    val binding: ViewCameraTileBinding =
        ViewCameraTileBinding.inflate(LayoutInflater.from(context), this, true)

    var player: CameraPlayer? = null
        private set

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true

        binding.tvCameraName.text = config.name
        binding.tvCameraIp.text = config.ip
        binding.tvCodecInfo.text = if (config.enabled) "• HEVC" else "• Pendente"

        // TV Remote Focus handling: subtle scale & elevation
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                animate().scaleX(1.03f).scaleY(1.03f).setDuration(150).start()
                elevation = 12f
            } else {
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                elevation = 2f
            }
        }

        setOnClickListener {
            if (config.enabled) {
                onTileClicked(config)
            }
        }

        if (!config.enabled) {
            applyDisabledState()
        }
    }

    val videoLayout: VLCVideoLayout
        get() = binding.videoLayout

    fun bindPlayer(cameraPlayer: CameraPlayer) {
        this.player = cameraPlayer
        cameraPlayer.attachViews(binding.videoLayout)
    }

    fun unbindPlayer() {
        player?.detachViews()
        this.player = null
    }

    override fun onStateChanged(camera: CameraConfig, state: CameraState, message: String?) {
        when (state) {
            CameraState.LIVE -> {
                binding.placeholderBg.visibility = View.GONE
                binding.layoutCenterStatus.visibility = View.GONE
                binding.progressBar.visibility = View.GONE

                binding.tvBadgeStatus.visibility = View.VISIBLE
                binding.tvBadgeStatus.text = context.getString(R.string.status_live)
                binding.tvBadgeStatus.setBackgroundResource(R.drawable.badge_live)
                binding.tvBadgeStatus.setTextColor(
                    ContextCompat.getColor(context, R.color.status_live)
                )
            }
            CameraState.CONNECTING -> {
                binding.layoutCenterStatus.visibility = View.VISIBLE
                binding.progressBar.visibility = View.VISIBLE
                binding.tvCenterMessage.text = message ?: context.getString(R.string.status_connecting)

                binding.tvBadgeStatus.visibility = View.VISIBLE
                binding.tvBadgeStatus.text = "CONECTANDO"
                binding.tvBadgeStatus.setBackgroundResource(R.drawable.badge_status)
                binding.tvBadgeStatus.setTextColor(
                    ContextCompat.getColor(context, R.color.status_connecting)
                )
            }
            CameraState.RECONNECTING -> {
                binding.layoutCenterStatus.visibility = View.VISIBLE
                binding.progressBar.visibility = View.VISIBLE
                binding.tvCenterMessage.text = message ?: context.getString(R.string.status_reconnecting)

                binding.tvBadgeStatus.visibility = View.VISIBLE
                binding.tvBadgeStatus.text = "RECONECTANDO"
                binding.tvBadgeStatus.setBackgroundResource(R.drawable.badge_status)
                binding.tvBadgeStatus.setTextColor(
                    ContextCompat.getColor(context, R.color.status_connecting)
                )
            }
            CameraState.OFFLINE -> {
                binding.placeholderBg.visibility = View.VISIBLE
                binding.layoutCenterStatus.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.tvCenterMessage.text = message ?: context.getString(R.string.status_offline)

                binding.tvBadgeStatus.visibility = View.VISIBLE
                binding.tvBadgeStatus.text = "OFFLINE"
                binding.tvBadgeStatus.setBackgroundResource(R.drawable.badge_status)
                binding.tvBadgeStatus.setTextColor(
                    ContextCompat.getColor(context, R.color.status_error)
                )
            }
            CameraState.DISABLED -> {
                applyDisabledState()
            }
        }
    }

    /** Indicador de gravação contínua (DVR) desta câmera. */
    fun setRecording(recording: Boolean) {
        binding.tvRecBadge.visibility = if (recording) View.VISIBLE else View.GONE
    }

    override fun onAudioStateChanged(camera: CameraConfig, isMuted: Boolean) {
        binding.ivAudioState.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    private fun applyDisabledState() {
        binding.placeholderBg.visibility = View.VISIBLE
        binding.layoutCenterStatus.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        binding.tvCenterMessage.text = "Configuração NVR indisponível no firmware"

        binding.tvBadgeStatus.visibility = View.VISIBLE
        binding.tvBadgeStatus.text = context.getString(R.string.status_disabled)
        binding.tvBadgeStatus.setBackgroundResource(R.drawable.badge_status)
        binding.tvBadgeStatus.setTextColor(
            ContextCompat.getColor(context, R.color.status_disabled)
        )
        alpha = 0.55f
    }
}
