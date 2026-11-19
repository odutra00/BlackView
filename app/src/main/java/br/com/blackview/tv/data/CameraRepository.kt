package br.com.blackview.tv.data

import android.content.Context
import android.content.SharedPreferences
import br.com.blackview.tv.BuildConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistência da configuração das câmeras (SharedPreferences/JSON).
 *
 * As senhas NÃO ficam no código-fonte: na primeira execução a senha inicial vem de
 * BuildConfig.DEFAULT_CAMERA_PASSWORD (lida de local.properties, não versionado) e
 * depois pode ser alterada na tela Ajustes.
 */
class CameraRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("blackview_cameras", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getCameras(): List<CameraConfig> {
        val json = prefs.getString(KEY_CAMERAS, null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<CameraConfig>>() {}.type
                val parsed: List<CameraConfig>? = gson.fromJson(json, type)
                if (!parsed.isNullOrEmpty()) return parsed.map { sanitize(it) }
            } catch (_: Exception) {
                // JSON corrompido: recria com os padrões abaixo
            }
        }
        val defaults = getDefaultCameras()
        saveCameras(defaults)
        return defaults
    }

    fun saveCameras(cameras: List<CameraConfig>) {
        prefs.edit()
            .putString(KEY_CAMERAS, gson.toJson(cameras))
            .putLong(KEY_VERSION, configVersion + 1)
            .apply()
    }

    fun updateCamera(updated: CameraConfig) {
        val current = getCameras().toMutableList()
        val index = current.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            current[index] = updated
            saveCameras(current)
        }
    }

    /** Incrementado a cada gravação; permite às telas detectar mudanças de configuração. */
    val configVersion: Long
        get() = prefs.getLong(KEY_VERSION, 0L)

    /**
     * O Gson ignora os valores padrão do construtor Kotlin; campos ausentes no JSON
     * chegam como null mesmo sendo não anuláveis. Normaliza aqui.
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    private fun sanitize(c: CameraConfig): CameraConfig = CameraConfig(
        id = c.id,
        name = c.name ?: "Câmera ${c.id}",
        ip = c.ip ?: "",
        port = if (c.port > 0) c.port else 554,
        path = c.path ?: "/onvif1",
        username = c.username ?: "admin",
        password = c.password ?: "",
        enabled = c.enabled,
        firmware = c.firmware ?: ""
    )

    private fun getDefaultCameras(): List<CameraConfig> {
        val pwd = BuildConfig.DEFAULT_CAMERA_PASSWORD
        return listOf(
            CameraConfig(1, "Câmera 1", "192.168.0.141", password = pwd, enabled = true, firmware = "40.1.27"),
            // Câmeras 2 e 4: sem opção NVR no firmware (fora do escopo inicial)
            CameraConfig(2, "Câmera 2", "192.168.0.122", password = pwd, enabled = false, firmware = "40.0.40"),
            CameraConfig(3, "Câmera 3", "192.168.0.103", password = pwd, enabled = true, firmware = "40.0.40"),
            CameraConfig(4, "Câmera 4", "192.168.0.137", password = pwd, enabled = false, firmware = "40.0.40"),
            CameraConfig(5, "Câmera 5", "192.168.0.150", password = pwd, enabled = true, firmware = "33.0.25")
        )
    }

    companion object {
        private const val KEY_CAMERAS = "cameras_list"
        private const val KEY_VERSION = "config_version"
    }
}
