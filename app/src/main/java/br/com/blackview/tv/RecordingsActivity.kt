package br.com.blackview.tv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import br.com.blackview.tv.data.CameraRepository
import br.com.blackview.tv.data.DvrSettings
import br.com.blackview.tv.data.RecordingFile
import br.com.blackview.tv.data.RecordingManager
import br.com.blackview.tv.data.formatGb
import br.com.blackview.tv.databinding.ActivityRecordingsBinding
import br.com.blackview.tv.dvr.DvrState
import br.com.blackview.tv.dvr.DvrStatus
import br.com.blackview.tv.ui.RecordingAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var recordingManager: RecordingManager
    private lateinit var adapter: RecordingAdapter

    private var selectedCameraId: Int? = null
    private val filterButtons = mutableListOf<Pair<Int?, Button>>()
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recordingManager = RecordingManager(this)

        setupRecyclerView()
        binding.btnDvrSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Atualiza a lista periodicamente (novos segmentos, retenção)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(60_000)
                    loadRecordings()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DvrState.status.collect { renderDvr(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupFilters()
        loadRecordings()
    }

    private fun setupRecyclerView() {
        adapter = RecordingAdapter(
            items = emptyList(),
            onItemClick = { openPlayback(it) },
            onItemLongClick = { confirmDelete(it) }
        )
        binding.recyclerRecordings.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecordings.adapter = adapter
    }

    private fun setupFilters() {
        val hadFocus = binding.layoutFilters.hasFocus() || filterButtons.isEmpty()
        binding.layoutFilters.removeAllViews()
        filterButtons.clear()

        val cameras = CameraRepository(this).getCameras()
        addFilter(null, "● Todas as Câmeras")
        cameras.forEach { cam ->
            addFilter(cam.id, cam.name + if (cam.enabled) "" else " (desativada)")
        }
        if (selectedCameraId != null && filterButtons.none { it.first == selectedCameraId }) {
            selectedCameraId = null
        }
        highlightFilter()
        if (hadFocus) {
            filterButtons.firstOrNull { it.first == selectedCameraId }?.second?.requestFocus()
        }
    }

    private fun addFilter(cameraId: Int?, label: String) {
        val btn = Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setBackgroundResource(R.drawable.btn_filter_selector)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), 0, dp(12), 0)
            isFocusable = true
            setOnClickListener {
                selectedCameraId = cameraId
                highlightFilter()
                loadRecordings()
            }
        }
        binding.layoutFilters.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { bottomMargin = dp(6) })
        filterButtons += cameraId to btn
    }

    private fun highlightFilter() {
        filterButtons.forEach { (id, btn) ->
            val active = id == selectedCameraId
            btn.isSelected = active
            btn.setTextColor(ContextCompat.getColor(this,
                if (active) R.color.text_primary else R.color.text_secondary))
        }
    }

    private fun loadRecordings() {
        loadJob?.cancel()
        val filter = selectedCameraId
        loadJob = lifecycleScope.launch {
            val active = DvrState.status.value.activePaths
            val recordings = withContext(Dispatchers.IO) {
                recordingManager.getRecordings(filter, active)
            }
            adapter.updateData(recordings)

            val filterName = filterButtons.firstOrNull { it.first == filter }?.second?.text
                ?.toString()?.removePrefix("● ") ?: "Todas as Câmeras"
            binding.tvRecordingsTitle.text = "$filterName (${recordings.size} vídeos)"

            val empty = recordings.isEmpty()
            binding.recyclerRecordings.visibility = if (empty) View.GONE else View.VISIBLE
            binding.layoutEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
            val totalBytes = recordings.sumOf { it.sizeBytes }
            binding.tvStorageUsed.text = String.format(Locale.US, "Gravações listadas: %s", formatGb(totalBytes))
        }
    }

    private fun renderDvr(s: DvrStatus) {
        val dvrOn = DvrSettings(this).enabled
        binding.tvStorageTitle.text = when {
            !dvrOn -> "DVR desligado"
            !s.storageAvailable -> "DVR aguardando destino"
            else -> "● DVR gravando ${s.recordingCount} câmera(s)"
        }
        binding.tvStorageFree.text = when {
            !dvrOn -> "Ative em ⚙ Ajustes"
            !s.storageAvailable -> s.message ?: "Destino indisponível"
            else -> "${s.storageLabel}: ${formatGb(s.freeBytes)} livres"
        }
        binding.tvEmptyHint.text = if (dvrOn)
            "O DVR está ativo. Os arquivos aparecem aqui a cada segmento gravado."
        else "Ative a gravação contínua em ⚙ Ajustes do DVR."
    }

    private fun openPlayback(recording: RecordingFile) {
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_FILE_PATH, recording.file.absolutePath)
            putExtra(PlaybackActivity.EXTRA_CAMERA_NAME, recording.cameraName)
            putExtra(PlaybackActivity.EXTRA_CAMERA_ID, recording.cameraId)
            putExtra(PlaybackActivity.EXTRA_TIMESTAMP, recording.formattedDate)
        }
        startActivity(intent)
    }

    private fun confirmDelete(recording: RecordingFile) {
        if (recording.isActive) {
            Toast.makeText(this, "Este arquivo está sendo gravado agora", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Excluir Gravação")
            .setMessage("Deseja realmente excluir a gravação de ${recording.cameraName} (${recording.formattedRange})?")
            .setPositiveButton("Excluir") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { recordingManager.deleteRecording(recording.file) }
                    Toast.makeText(this@RecordingsActivity,
                        if (ok) "Gravação excluída" else "Falha ao excluir gravação", Toast.LENGTH_SHORT).show()
                    loadRecordings()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
