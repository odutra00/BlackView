package br.com.blackview.tv.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.util.Locale

/**
 * Um local onde o app pode gravar sem permissões especiais: a pasta privada do app
 * no armazenamento interno ou em um volume removível (pendrive / HD USB).
 *
 * Usa Context.getExternalFilesDirs(), que no Android 14 devolve
 * /storage/emulated/0/Android/data/<pkg>/files e /storage/XXXX-XXXX/Android/data/<pkg>/files
 * para cada pendrive montado. O LibVLC precisa de um caminho de arquivo real, por isso
 * não usamos o Storage Access Framework (content://).
 *
 * Atenção: arquivos nessas pastas são apagados se o app for desinstalado.
 */
data class StorageLocation(
    val id: String,
    val label: String,
    val removable: Boolean,
    val appDir: File
) {
    val recordingsDir: File get() = File(appDir, "recordings")

    val freeBytes: Long get() = try { StatFs(appDir.absolutePath).availableBytes } catch (_: Exception) { 0L }
    val totalBytes: Long get() = try { StatFs(appDir.absolutePath).totalBytes } catch (_: Exception) { 0L }

    fun describe(): String = "$label • ${formatGb(freeBytes)} livres de ${formatGb(totalBytes)}"
}

fun formatGb(bytes: Long): String =
    String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024.0 * 1024 * 1024))

object StorageLocations {

    const val INTERNAL_ID = "internal"

    fun list(context: Context): List<StorageLocation> {
        val sm = context.getSystemService(StorageManager::class.java)
        val result = mutableListOf<StorageLocation>()
        val dirs = try { context.getExternalFilesDirs(null) } catch (_: Exception) { emptyArray() }

        for (dir in dirs) {
            if (dir == null) continue
            if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) continue
            if (!dir.exists() && !dir.mkdirs()) continue
            val vol = try { sm?.getStorageVolume(dir) } catch (_: Exception) { null }
            val primary = vol?.isPrimary ?: (result.isEmpty())
            val removable = vol?.isRemovable ?: !primary
            val id = if (primary) INTERNAL_ID else (vol?.uuid ?: dir.absolutePath)
            val label = when {
                primary -> "Armazenamento interno"
                else -> {
                    val desc = try { vol?.getDescription(context) } catch (_: Exception) { null }
                    "USB: " + (desc?.takeIf { it.isNotBlank() } ?: vol?.uuid ?: "Pendrive")
                }
            }
            if (result.none { it.id == id }) {
                result.add(StorageLocation(id, label, removable, dir))
            }
        }

        if (result.none { it.id == INTERNAL_ID }) {
            // Fallback extremo: armazenamento privado do app
            result.add(0, StorageLocation(INTERNAL_ID, "Armazenamento interno", false, context.filesDir))
        }
        return result
    }

    fun find(context: Context, id: String?): StorageLocation? =
        list(context).firstOrNull { it.id == (id ?: INTERNAL_ID) }
}
