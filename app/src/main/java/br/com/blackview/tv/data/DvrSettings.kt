package br.com.blackview.tv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferências do DVR (gravação contínua).
 */
class DvrSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("blackview_dvr", Context.MODE_PRIVATE)

    /** Gravação contínua ligada. Padrão: desligada até o usuário escolher o destino. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /**
     * Destino das gravações: [StorageLocation.id] ("internal" ou UUID do pendrive).
     * null = armazenamento interno.
     */
    var storageId: String?
        get() = prefs.getString(KEY_STORAGE, null)
        set(v) = prefs.edit().putString(KEY_STORAGE, v).apply()

    /** Descrição do último destino escolhido (para exibir quando o pendrive estiver ausente). */
    var storageLabel: String?
        get() = prefs.getString(KEY_STORAGE_LABEL, null)
        set(v) = prefs.edit().putString(KEY_STORAGE_LABEL, v).apply()

    /** Duração de cada arquivo, em minutos (alinhada ao relógio: 15 → :00, :15, :30, :45). */
    var segmentMinutes: Int
        get() = prefs.getInt(KEY_SEGMENT, 30)
        set(v) = prefs.edit().putInt(KEY_SEGMENT, v).apply()

    /** Espaço livre mínimo a preservar no destino, em GB. */
    var minFreeGb: Int
        get() = prefs.getInt(KEY_MIN_FREE, 2)
        set(v) = prefs.edit().putInt(KEY_MIN_FREE, v).apply()

    /** Limite total ocupado pelas gravações, em GB. 0 = sem limite (usa até o espaço livre mínimo). */
    var maxTotalGb: Int
        get() = prefs.getInt(KEY_MAX_TOTAL, 0)
        set(v) = prefs.edit().putInt(KEY_MAX_TOTAL, v).apply()

    /** Iniciar a gravação automaticamente quando a TV liga. */
    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_BOOT, true)
        set(v) = prefs.edit().putBoolean(KEY_BOOT, v).apply()

    /**
     * IDs das câmeras a gravar. null = nunca configurado (grava todas as câmeras ativas).
     */
    var recordCameraIds: Set<Int>?
        get() = prefs.getStringSet(KEY_CAMERAS, null)?.mapNotNull { it.toIntOrNull() }?.toSet()
        set(v) = prefs.edit().putStringSet(KEY_CAMERAS, v?.map { it.toString() }?.toSet()).apply()

    fun shouldRecord(camera: CameraConfig): Boolean {
        if (!camera.enabled || camera.needsPassword) return false
        return recordCameraIds?.contains(camera.id) ?: true
    }

    fun setRecordCamera(cameraId: Int, record: Boolean, allCameras: List<CameraConfig>) {
        val current = recordCameraIds?.toMutableSet()
            ?: allCameras.filter { it.enabled }.map { it.id }.toMutableSet()
        if (record) current.add(cameraId) else current.remove(cameraId)
        recordCameraIds = current
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STORAGE = "storage_id"
        private const val KEY_STORAGE_LABEL = "storage_label"
        private const val KEY_SEGMENT = "segment_minutes"
        private const val KEY_MIN_FREE = "min_free_gb"
        private const val KEY_MAX_TOTAL = "max_total_gb"
        private const val KEY_BOOT = "start_on_boot"
        private const val KEY_CAMERAS = "record_camera_ids"

        val SEGMENT_OPTIONS = intArrayOf(15, 30, 60)
        val MIN_FREE_OPTIONS = intArrayOf(1, 2, 5, 10, 20)
        val MAX_TOTAL_OPTIONS = intArrayOf(0, 8, 16, 32, 64, 128, 256, 512, 1024)
    }
}
