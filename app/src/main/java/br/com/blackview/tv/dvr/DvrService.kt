package br.com.blackview.tv.dvr

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import br.com.blackview.tv.MainActivity
import br.com.blackview.tv.R
import br.com.blackview.tv.data.CameraRepository
import br.com.blackview.tv.data.DvrSettings
import br.com.blackview.tv.data.RecordingManager
import br.com.blackview.tv.data.StorageLocation
import br.com.blackview.tv.data.StorageLocations
import br.com.blackview.tv.data.formatGb
import org.videolan.libvlc.LibVLC

/**
 * Serviço em primeiro plano do DVR: mantém um [SegmentRecorder] por câmera,
 * aplica a retenção de espaço e reage à conexão/remoção do pendrive.
 *
 * Ações: [ACTION_START] (ou reaplicar configuração) e [ACTION_STOP].
 */
class DvrService : Service() {

    private val tag = "DvrService"
    private val worker = HandlerThread("dvr-service").apply { start() }
    private val handler = Handler(worker.looper)

    private var libVLC: LibVLC? = null
    private val recorders = mutableListOf<SegmentRecorder>()
    private var activeLocation: StorageLocation? = null
    private var activeSegmentMinutes = 0

    private lateinit var settings: DvrSettings
    private lateinit var recordingManager: RecordingManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val housekeeping = object : Runnable {
        override fun run() {
            runRetention()
            checkStorage()
            updateNotification()
            handler.postDelayed(this, HOUSEKEEPING_MS)
        }
    }

    /** Pendrive conectado/removido → reavalia o destino. */
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(tag, "Evento de armazenamento: ${intent.action} ${intent.data}")
            handler.postDelayed({ applyConfig() }, 2_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = DvrSettings(this)
        recordingManager = RecordingManager(this)
        createChannel()
        goForeground("Iniciando DVR…")
        acquireLocks()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        DvrState.update { it.copy(serviceRunning = true) }
        handler.postDelayed(housekeeping, HOUSEKEEPING_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(buildStatusText())
        when (intent?.action) {
            ACTION_STOP -> {
                handler.post { stopAllRecorders() }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> handler.post { applyConfig() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(mediaReceiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            stopAllRecorders()
            libVLC?.release()
            libVLC = null
            done.countDown()
        }
        done.await(8, java.util.concurrent.TimeUnit.SECONDS)
        worker.quitSafely()
        releaseLocks()
        DvrState.reset()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------

    /** Lê as configurações e (re)cria os gravadores. Roda na thread do serviço. */
    private fun applyConfig() {
        if (!settings.enabled) {
            Log.i(tag, "DVR desligado nas configurações; encerrando serviço")
            stopAllRecorders()
            stopSelf()
            return
        }

        val location = StorageLocations.find(this, settings.storageId)
        if (location == null) {
            stopAllRecorders()
            activeLocation = null
            DvrState.update {
                it.copy(
                    storageLabel = settings.storageLabel ?: "Pendrive",
                    storageAvailable = false,
                    freeBytes = 0,
                    message = "Destino não encontrado: conecte o pendrive"
                )
            }
            updateNotification()
            return
        }

        val cameras = CameraRepository(this).getCameras().filter { settings.shouldRecord(it) }
        val wanted = cameras.map { it.copy() }
        val sameSetup = location.id == activeLocation?.id &&
            settings.segmentMinutes == activeSegmentMinutes &&
            recorders.map { it.camera } == wanted &&
            recorders.isNotEmpty()
        if (sameSetup) {
            updateNotification()
            return
        }

        stopAllRecorders()
        activeLocation = location
        activeSegmentMinutes = settings.segmentMinutes
        location.recordingsDir.mkdirs()

        val vlc = libVLC ?: LibVLC(this, arrayListOf(
            "--no-stats", "--no-osd", "--no-video-title-show",
            // Opção global (não funciona por mídia): ver SegmentRecorder.MUX_CACHING_MS
            "--sout-mux-caching=${SegmentRecorder.MUX_CACHING_MS}"
        ))
            .also { libVLC = it }

        runRetention()
        for (cam in wanted) {
            val rec = SegmentRecorder(
                libVLC = vlc,
                camera = cam,
                location = location,
                recordingManager = recordingManager,
                segmentMinutes = settings.segmentMinutes,
                beforeNewSegment = { runRetention() }
            )
            recorders += rec
            rec.start()
        }

        DvrState.update {
            it.copy(
                storageLabel = location.label,
                storageAvailable = true,
                freeBytes = location.freeBytes,
                message = if (wanted.isEmpty()) "Nenhuma câmera selecionada para gravação" else null
            )
        }
        Log.i(tag, "DVR ativo: ${wanted.size} câmera(s) → ${location.recordingsDir}")
        updateNotification()
    }

    private fun stopAllRecorders() {
        recorders.forEach { it.stopAndRelease() }
        recorders.clear()
        DvrState.update { it.copy(cameras = emptyMap()) }
    }

    @Synchronized
    private fun runRetention() {
        val loc = activeLocation ?: return
        try {
            val n = recordingManager.enforceRetention(
                location = loc,
                minFreeBytes = settings.minFreeGb.toLong() * GB,
                maxTotalBytes = settings.maxTotalGb.toLong() * GB,
                activePaths = DvrState.status.value.activePaths
            )
            if (n > 0) Log.i(tag, "Retenção: $n arquivo(s) antigo(s) excluído(s)")
            DvrState.update { it.copy(freeBytes = loc.freeBytes) }
        } catch (e: Exception) {
            Log.e(tag, "Erro na retenção", e)
        }
    }

    /** Detecta remoção do pendrive mesmo sem broadcast (alguns firmwares não enviam). */
    private fun checkStorage() {
        val loc = activeLocation
        val present = StorageLocations.find(this, settings.storageId)
        if (loc != null && present == null) {
            Log.w(tag, "Destino ${loc.label} desapareceu")
            applyConfig()
        } else if (loc == null && present != null && settings.enabled) {
            Log.i(tag, "Destino ${present.label} disponível novamente")
            applyConfig()
        }
    }

    // ---------------------------------------------------------------------------------

    private fun buildStatusText(): String {
        val s = DvrState.status.value
        if (!s.storageAvailable) return s.message ?: "Destino indisponível"
        val total = recorders.size
        return "Gravando ${s.recordingCount}/$total câmera(s) • ${s.storageLabel ?: ""} • " +
            "${formatGb(s.freeBytes)} livres"
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Gravação contínua (DVR)", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("BlackView DVR")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(buildStatusText()))
        } catch (_: Exception) {
            // Sem permissão POST_NOTIFICATIONS: o serviço continua funcionando
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlackView:DVR").apply {
                setReferenceCounted(false)
                acquire()
            }
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BlackView:DVR").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(tag, "Não foi possível adquirir wake/wifi lock", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_START = "br.com.blackview.tv.dvr.START"
        const val ACTION_STOP = "br.com.blackview.tv.dvr.STOP"
        private const val CHANNEL_ID = "dvr"
        private const val NOTIFICATION_ID = 1001
        private const val HOUSEKEEPING_MS = 60_000L
        private const val GB = 1024L * 1024 * 1024

        /** Inicia o serviço (se o DVR estiver ligado) ou reaplica a configuração. */
        fun sync(context: Context) {
            val settings = DvrSettings(context)
            if (settings.enabled) {
                val i = Intent(context, DvrService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, i)
            } else if (DvrState.status.value.serviceRunning) {
                context.startService(Intent(context, DvrService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
