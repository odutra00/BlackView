package br.com.blackview.tv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import br.com.blackview.tv.data.CameraConfig
import br.com.blackview.tv.data.CameraRepository
import br.com.blackview.tv.data.DvrSettings
import br.com.blackview.tv.data.StorageLocation
import br.com.blackview.tv.data.StorageLocations
import br.com.blackview.tv.data.formatGb
import br.com.blackview.tv.databinding.ActivitySettingsBinding
import br.com.blackview.tv.dvr.DvrService
import java.util.Locale

/**
 * Ajustes pelo controle remoto: DVR (liga/desliga, destino interno/pendrive,
 * duração dos arquivos, retenção, câmeras gravadas) e câmeras (nome, IP, senha NVR).
 *
 * As alterações só são aplicadas ao pressionar "Salvar e aplicar".
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repo: CameraRepository
    private lateinit var dvr: DvrSettings

    // Estado pendente (aplicado ao salvar)
    private var pEnabled = false
    private var pStorageId: String? = null
    private var pSegment = 30
    private var pMinFree = 2
    private var pMaxTotal = 0
    private var pBoot = true
    private val pRecord = mutableMapOf<Int, Boolean>()

    private var locations: List<StorageLocation> = emptyList()
    private lateinit var cameras: List<CameraConfig>

    private class CameraEditors(
        val camera: CameraConfig,
        var enabled: Boolean,
        val name: EditText,
        val ip: EditText,
        val password: EditText
    )
    private val cameraEditors = mutableListOf<CameraEditors>()
    private var estimateView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = CameraRepository(this)
        dvr = DvrSettings(this)
        cameras = repo.getCameras()
        locations = StorageLocations.list(this)

        pEnabled = dvr.enabled
        pStorageId = dvr.storageId ?: StorageLocations.INTERNAL_ID
        pSegment = dvr.segmentMinutes
        pMinFree = dvr.minFreeGb
        pMaxTotal = dvr.maxTotalGb
        pBoot = dvr.startOnBoot
        cameras.forEach { pRecord[it.id] = dvr.recordCameraIds?.contains(it.id) ?: it.enabled }

        buildDvrSection()
        buildCamerasSection()

        binding.btnSave.setOnClickListener { save() }
        binding.btnCancel.setOnClickListener { finish() }

        if (intent.getBooleanExtra(EXTRA_FOCUS_CAMERAS, false)) {
            cameraEditors.firstOrNull { it.camera.enabled }?.password?.let { pw ->
                binding.settingsScroll.post {
                    binding.settingsScroll.smoothScrollTo(0, (pw.parent as View).top)
                    pw.requestFocus()
                }
            }
            Toast.makeText(this, "Informe a senha NVR das câmeras ativas", Toast.LENGTH_LONG).show()
        } else {
            binding.settingsContainer.getChildAt(1)?.requestFocus()
        }
    }

    // ------------------------------------------------------------------ DVR

    private fun buildDvrSection() {
        val c = binding.settingsContainer
        c.addView(sectionTitle("GRAVAÇÃO CONTÍNUA (DVR)"))

        c.addView(cycleRow({ "Gravação contínua: " + if (pEnabled) "LIGADA" else "DESLIGADA" }) {
            pEnabled = !pEnabled
        })

        c.addView(cycleRow({ "Destino das gravações: " + storageText() }) {
            locations = StorageLocations.list(this)
            if (locations.isNotEmpty()) {
                val idx = locations.indexOfFirst { it.id == pStorageId }
                pStorageId = locations[(idx + 1).mod(locations.size)].id
            }
        })
        c.addView(hint("Conecte o pendrive na TV e pressione OK aqui para alternar entre armazenamento interno e USB. " +
            "As gravações ficam em Android/data/br.com.blackview.tv/files/recordings no pendrive."))

        c.addView(cycleRow({ "Duração de cada arquivo: $pSegment min" }) {
            pSegment = next(DvrSettings.SEGMENT_OPTIONS, pSegment)
        })
        c.addView(cycleRow({ "Espaço livre mínimo no destino: $pMinFree GB" }) {
            pMinFree = next(DvrSettings.MIN_FREE_OPTIONS, pMinFree)
        })
        c.addView(cycleRow({
            "Limite das gravações: " + if (pMaxTotal == 0) "sem limite (até o espaço livre mínimo)" else "$pMaxTotal GB"
        }) {
            pMaxTotal = next(DvrSettings.MAX_TOTAL_OPTIONS, pMaxTotal)
        })
        c.addView(hint("Ao atingir o limite, as gravações mais antigas são excluídas automaticamente (gravação circular)."))

        c.addView(cycleRow({ "Iniciar gravação quando a TV ligar: " + if (pBoot) "Sim" else "Não" }) {
            pBoot = !pBoot
        })

        for (cam in cameras.filter { it.enabled }) {
            c.addView(cycleRow({ "Gravar ${cam.name}: " + if (pRecord[cam.id] == true) "Sim" else "Não" }) {
                pRecord[cam.id] = pRecord[cam.id] != true
            })
        }

        estimateView = hint("").also { c.addView(it) }
        updateEstimate()
    }

    private fun storageText(): String {
        val loc = locations.firstOrNull { it.id == pStorageId }
        return loc?.describe() ?: "${dvr.storageLabel ?: "Pendrive"} (não conectado)"
    }

    private fun updateEstimate() {
        val n = pRecord.count { (id, rec) -> rec && cameras.firstOrNull { it.id == id }?.enabled == true }
        val perHour = GB_PER_HOUR_PER_CAMERA * n
        val loc = locations.firstOrNull { it.id == pStorageId }
        val text = StringBuilder()
        text.append(String.format(Locale("pt", "BR"),
            "Estimativa: ~%.1f GB/hora por câmera (1080p H.265 ≈ 1,3 Mbps). %d câmera(s) ≈ %.0f GB/dia.",
            GB_PER_HOUR_PER_CAMERA, n, perHour * 24))
        if (loc != null && n > 0) {
            val usable = if (pMaxTotal > 0) pMaxTotal.toDouble()
            else (loc.totalBytes / GB - pMinFree).coerceAtLeast(0.0)
            val hours = usable / perHour
            text.append(String.format(Locale("pt", "BR"),
                " Histórico aproximado no destino: %.0f h (%.1f dias).", hours, hours / 24))
        }
        estimateView?.text = text
    }

    // ------------------------------------------------------------------ Câmeras

    private fun buildCamerasSection() {
        val c = binding.settingsContainer
        c.addView(sectionTitle("CÂMERAS"))
        c.addView(hint("Senha NVR configurada no firmware de cada câmera (usuário admin). " +
            "Use o teclado da TV para editar os campos."))

        for (cam in cameras) {
            val header = TextView(this).apply {
                text = "${cam.name}  •  firmware ${cam.firmware.ifBlank { "?" }}"
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 15f
                setPadding(0, dp(14), 0, dp(4))
            }
            c.addView(header)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = editField(cam.name, "Nome", InputType.TYPE_CLASS_TEXT)
            val ip = editField(cam.ip, "IP", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            val pwd = editField(cam.password, "Senha NVR",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            val editors = CameraEditors(cam, cam.enabled, name, ip, pwd)

            val toggle = Button(this).apply {
                styleButton(this)
                fun refresh() { text = if (editors.enabled) "● Ativa" else "○ Desativada" }
                refresh()
                setOnClickListener {
                    editors.enabled = !editors.enabled
                    refresh()
                }
            }
            row.addView(toggle, LinearLayout.LayoutParams(dp(170), dp(46)).apply { marginEnd = dp(10) })
            row.addView(name, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) })
            row.addView(ip, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) })
            row.addView(pwd, LinearLayout.LayoutParams(0, dp(46), 1f))
            c.addView(row)
            cameraEditors += editors
        }
    }

    // ------------------------------------------------------------------ Salvar

    private fun save() {
        val ipRegex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        val updated = mutableListOf<CameraConfig>()
        for (e in cameraEditors) {
            val ip = e.ip.text.toString().trim()
            if (e.enabled && !ipRegex.matches(ip)) {
                e.ip.requestFocus()
                Toast.makeText(this, "IP inválido para ${e.camera.name}", Toast.LENGTH_LONG).show()
                return
            }
            updated += e.camera.copy(
                name = e.name.text.toString().trim().ifBlank { e.camera.name },
                ip = ip,
                password = e.password.text.toString(),
                enabled = e.enabled
            )
        }

        val missingPwd = updated.filter { it.needsPassword }
        repo.saveCameras(updated)

        val chosen = locations.firstOrNull { it.id == pStorageId }
        dvr.enabled = pEnabled
        dvr.storageId = pStorageId
        if (chosen != null) dvr.storageLabel = chosen.label
        dvr.segmentMinutes = pSegment
        dvr.minFreeGb = pMinFree
        dvr.maxTotalGb = pMaxTotal
        dvr.startOnBoot = pBoot
        dvr.recordCameraIds = pRecord.filterValues { it }.keys

        if (pEnabled) requestNotificationPermission()
        DvrService.sync(this)

        val msg = when {
            missingPwd.isNotEmpty() -> "Salvo. Sem senha: ${missingPwd.joinToString { it.name }} (não será exibida/gravada)"
            pEnabled && chosen == null -> "Salvo. DVR aguardando o pendrive ser conectado"
            pEnabled -> "Salvo. DVR gravando em: ${chosen?.label}"
            else -> "Configurações salvas"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ------------------------------------------------------------------ Helpers de UI

    private fun cycleRow(label: () -> String, onClick: () -> Unit): Button =
        Button(this).apply {
            styleButton(this)
            text = label()
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(6)
            }
            setOnClickListener {
                onClick()
                refreshAllRows()
            }
            tag = label
        }

    @Suppress("UNCHECKED_CAST")
    private fun refreshAllRows() {
        val c = binding.settingsContainer
        for (i in 0 until c.childCount) {
            val v = c.getChildAt(i)
            val l = v.tag as? (() -> String) ?: continue
            (v as Button).text = l()
        }
        updateEstimate()
    }

    private fun styleButton(b: Button) {
        b.setBackgroundResource(R.drawable.btn_filter_selector)
        b.isAllCaps = false
        b.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        b.isFocusable = true
    }

    private fun editField(value: String, hintText: String, type: Int): EditText =
        EditText(this).apply {
            setText(value)
            hint = hintText
            // isSingleLine ANTES de inputType: setSingleLine() remove a máscara de senha
            isSingleLine = true
            inputType = type
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setBackgroundResource(R.drawable.btn_filter_selector)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), 0, dp(12), 0)
        }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        setTextColor(ContextCompat.getColor(context, R.color.primary_accent))
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        textSize = 12f
        setPadding(dp(4), 0, dp(4), dp(10))
    }

    private fun next(options: IntArray, current: Int): Int {
        val i = options.indexOf(current)
        return options[(i + 1).mod(options.size)]
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_FOCUS_CAMERAS = "focus_cameras"
        private const val GB = 1024.0 * 1024 * 1024
        /** Medido: 1,3 Mbps (vídeo+áudio) ≈ 0,55 GiB/h. */
        private const val GB_PER_HOUR_PER_CAMERA = 0.55
    }
}
