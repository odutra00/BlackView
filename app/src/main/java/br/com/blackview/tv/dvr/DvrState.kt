package br.com.blackview.tv.dvr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class RecorderStatus { STARTING, RECORDING, RETRYING, STOPPED }

data class CameraRecStatus(
    val cameraId: Int,
    val status: RecorderStatus,
    val currentFile: String? = null,
    val message: String? = null
)

data class DvrStatus(
    val serviceRunning: Boolean = false,
    /** Destino em uso (ou o escolhido, se indisponível). */
    val storageLabel: String? = null,
    val storageAvailable: Boolean = true,
    val freeBytes: Long = 0L,
    val cameras: Map<Int, CameraRecStatus> = emptyMap(),
    val message: String? = null
) {
    val recordingCount: Int get() = cameras.values.count { it.status == RecorderStatus.RECORDING }
    val activePaths: Set<String> get() = cameras.values.mapNotNull { it.currentFile }.toSet()
    fun isRecording(cameraId: Int): Boolean = cameras[cameraId]?.status == RecorderStatus.RECORDING
}

/** Estado do DVR compartilhado no processo (serviço → telas). */
object DvrState {
    private val _status = MutableStateFlow(DvrStatus())
    val status: StateFlow<DvrStatus> = _status

    fun update(block: (DvrStatus) -> DvrStatus) = _status.update(block)

    fun updateCamera(s: CameraRecStatus) = _status.update { it.copy(cameras = it.cameras + (s.cameraId to s)) }

    fun reset() { _status.value = DvrStatus() }
}
