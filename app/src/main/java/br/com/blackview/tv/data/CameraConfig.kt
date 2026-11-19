package br.com.blackview.tv.data

import android.net.Uri

data class CameraConfig(
    val id: Int,
    val name: String,
    val ip: String,
    val port: Int = 554,
    val path: String = "/onvif1",
    val username: String = "admin",
    val password: String = "",
    val enabled: Boolean = true,
    val firmware: String = ""
) {
    /** URL RTSP com credenciais codificadas (senhas com @, :, / etc. não quebram a URL). */
    val rtspUrl: String
        get() = "rtsp://${Uri.encode(username)}:${Uri.encode(password)}@$ip:$port$path"

    /** Câmera ativa mas sem senha NVR configurada. */
    val needsPassword: Boolean
        get() = enabled && password.isBlank()
}

enum class CameraState {
    DISABLED,
    CONNECTING,
    LIVE,
    RECONNECTING,
    OFFLINE
}
