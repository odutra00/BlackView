package br.com.blackview.tv.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingFile(
    val file: File,
    val cameraId: Int,
    val cameraName: String,
    /** Início da gravação (do nome do arquivo; para arquivos antigos, data de modificação). */
    val timestamp: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val storageLabel: String,
    /** Arquivo ainda sendo gravado pelo DVR. */
    val isActive: Boolean
) {
    val formattedDate: String
        get() = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    val formattedRange: String
        get() {
            val day = SimpleDateFormat("EEE dd/MM/yyyy", Locale("pt", "BR")).format(Date(timestamp))
            val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
            val start = hm.format(Date(timestamp))
            val end = if (isActive) "agora" else if (durationMs > 0) hm.format(Date(timestamp + durationMs)) else "?"
            return "$day  •  $start → $end"
        }

    val formattedSize: String
        get() {
            val mb = sizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
            else String.format(Locale.US, "%.1f MB", mb)
        }

    val formattedDuration: String
        get() = formatDuration(durationMs)
}

data class StorageInfo(
    val freeBytes: Long,
    val totalBytes: Long,
    val usedByRecordingsBytes: Long
) {
    val freeGb: Double get() = freeBytes.toDouble() / (1024 * 1024 * 1024)
    val totalGb: Double get() = totalBytes.toDouble() / (1024 * 1024 * 1024)
    val recordingsMb: Double get() = usedByRecordingsBytes.toDouble() / (1024 * 1024)
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/**
 * Organização dos arquivos:
 *   <destino>/recordings/camera_<id>/cam<id>_<yyyyMMdd-HHmmss>.avi
 *
 * Todas as funções fazem I/O de disco: chamar fora da thread principal.
 */
class RecordingManager(context: Context) {

    private val appContext = context.applicationContext
    private val tag = "RecordingManager"

    fun cameraDir(location: StorageLocation, cameraId: Int): File =
        File(location.recordingsDir, "camera_$cameraId").apply { if (!exists()) mkdirs() }

    fun newSegmentFile(location: StorageLocation, cameraId: Int, startMs: Long): File {
        val stamp = SimpleDateFormat(FILE_STAMP, Locale.US).format(Date(startMs))
        return File(cameraDir(location, cameraId), "cam${cameraId}_$stamp.$EXTENSION")
    }

    /**
     * Lista as gravações de todos os destinos disponíveis (interno + pendrives montados),
     * mais recentes primeiro.
     */
    fun getRecordings(cameraIdFilter: Int? = null, activePaths: Set<String> = emptySet()): List<RecordingFile> {
        val cameraMap = CameraRepository(appContext).getCameras().associateBy { it.id }
        val list = mutableListOf<RecordingFile>()
        for (loc in StorageLocations.list(appContext)) {
            list += scanLocation(loc, cameraIdFilter, cameraMap, activePaths)
        }
        return list.sortedByDescending { it.timestamp }
    }

    /** Próxima gravação (cronológica) da mesma câmera, para reprodução contínua. */
    fun nextRecording(current: File, cameraId: Int, activePaths: Set<String> = emptySet()): RecordingFile? {
        val all = getRecordings(cameraId, activePaths).sortedBy { it.timestamp }
        val idx = all.indexOfFirst { it.file.absolutePath == current.absolutePath }
        return if (idx >= 0) all.getOrNull(idx + 1) else null
    }

    private fun scanLocation(
        loc: StorageLocation,
        cameraIdFilter: Int?,
        cameraMap: Map<Int, CameraConfig>,
        activePaths: Set<String>
    ): List<RecordingFile> {
        val base = loc.recordingsDir
        if (!base.isDirectory) return emptyList()
        val dirs = base.listFiles { f -> f.isDirectory && f.name.startsWith("camera_") } ?: return emptyList()
        val out = mutableListOf<RecordingFile>()
        val now = System.currentTimeMillis()

        for (dir in dirs) {
            val camId = dir.name.removePrefix("camera_").toIntOrNull() ?: continue
            if (cameraIdFilter != null && camId != cameraIdFilter) continue
            val camName = cameraMap[camId]?.name ?: "Câmera $camId"
            val files = dir.listFiles { f ->
                f.isFile && VIDEO_EXTENSIONS.any { f.name.lowercase(Locale.US).endsWith(it) }
            } ?: continue

            for (file in files) {
                val size = file.length()
                val active = file.absolutePath in activePaths
                val parsedStart = parseStart(file.name)
                val start: Long
                val duration: Long
                if (parsedStart != null) {
                    start = parsedStart
                    val end = if (active) now else file.lastModified()
                    duration = (end - start).coerceAtLeast(0)
                } else {
                    // Gravação manual antiga (vlc-record-*.avi): usa metadados
                    duration = probeDuration(file)
                    start = file.lastModified() - duration
                }
                if (size < MIN_VALID_BYTES && !active) continue
                out += RecordingFile(file, camId, camName, start, duration, size, loc.label, active)
            }
        }
        return out
    }

    private fun parseStart(name: String): Long? {
        val m = NAME_REGEX.matchEntire(name) ?: return null
        return try {
            SimpleDateFormat(FILE_STAMP, Locale.US).parse(m.groupValues[2])?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun probeDuration(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    fun deleteRecording(file: File): Boolean = try {
        file.exists() && file.delete().also { Log.d(tag, "Gravação excluída: ${file.name} ($it)") }
    } catch (e: Exception) {
        Log.e(tag, "Erro ao excluir ${file.name}", e)
        false
    }

    fun getStorageInfo(location: StorageLocation): StorageInfo {
        val used = location.recordingsDir.takeIf { it.isDirectory }
            ?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        return StorageInfo(location.freeBytes, location.totalBytes, used)
    }

    /**
     * Retenção circular: exclui as gravações mais antigas do destino até que
     *  - o espaço livre seja >= minFreeBytes, e
     *  - o total das gravações seja <= maxTotalBytes (se > 0).
     * Nunca exclui arquivos em gravação. Retorna o número de arquivos excluídos.
     */
    fun enforceRetention(
        location: StorageLocation,
        minFreeBytes: Long,
        maxTotalBytes: Long,
        activePaths: Set<String>
    ): Int {
        val cameraMap = CameraRepository(appContext).getCameras().associateBy { it.id }
        val files = scanLocation(location, null, cameraMap, activePaths)
            .filter { !it.isActive }
            .sortedBy { it.timestamp }
        var used = files.sumOf { it.sizeBytes } +
            activePaths.sumOf { p -> File(p).takeIf { it.exists() }?.length() ?: 0L }
        var free = location.freeBytes
        var deleted = 0

        for (rec in files) {
            val needFree = free < minFreeBytes
            val overQuota = maxTotalBytes > 0 && used > maxTotalBytes
            if (!needFree && !overQuota) break
            val size = rec.sizeBytes
            if (deleteRecording(rec.file)) {
                used -= size
                free += size
                deleted++
                Log.i(tag, "Retenção: excluído ${rec.file.name} (${location.label})")
            }
        }
        return deleted
    }

    companion object {
        const val EXTENSION = "avi"
        private const val FILE_STAMP = "yyyyMMdd-HHmmss"
        private const val MIN_VALID_BYTES = 32 * 1024L
        private val NAME_REGEX = Regex("""cam(\d+)_(\d{8}-\d{6})\.\w+""")
        private val VIDEO_EXTENSIONS = listOf(".avi", ".mp4", ".ts", ".mkv")
    }
}
