package ro.cretu.bonscanner

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private lateinit var status: TextView
    private lateinit var btnSend: Button
    private val spinner by lazy { findViewById<Spinner>(R.id.spCategory) }
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private val scanner by lazy {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(3)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL) // detecție margini, decupare, corecție perspectivă
                .build()
        )
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val pages = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages.orEmpty()
        if (pages.isEmpty()) return@registerForActivityResult
        findViewById<ImageView>(R.id.preview).apply { setImageURI(pages.first().imageUri); visibility = android.view.View.VISIBLE }
        recognize(pages.map { it.imageUri })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        btnSend = findViewById(R.id.btnSend)
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        val cbPersonal = findViewById<CheckBox>(R.id.cbPersonal)
        cbPersonal.setOnCheckedChangeListener { _, checked ->
            // banii personali nu trec prin contul firmei: plata se trece la Numerar
            if (checked) findViewById<RadioButton>(R.id.rbNumerar).isChecked = true
            findViewById<RadioButton>(R.id.rbBanca).isEnabled = !checked
        }
        findViewById<RadioGroup>(R.id.rgFlow).setOnCheckedChangeListener { _, id ->
            val plata = id == R.id.rbPlata
            cbPersonal.isEnabled = plata
            if (!plata) cbPersonal.isChecked = false
        }
        updateConnectionLabel()
        if (scriptUrl().isBlank()) showSettings()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ReceiptParser.CATEGORIES)
        spinner.setSelection(ReceiptParser.CATEGORIES.indexOf("Altele"))

        findViewById<Button>(R.id.btnAbout).setOnClickListener { showAbout() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { onUpdateClick() }
        checkForUpdate()
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            scanner.getStartScanIntent(this)
                .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { setStatus("Scanner indisponibil: ${it.message}", false) }
        }
        btnSend.setOnClickListener {
            val url = scriptUrl()
            if (url.isBlank()) showSettings() else send(url)
        }
    }

    private fun recognize(uris: List<Uri>) {
        setStatus("Recunosc textul…", null)
        btnSend.isEnabled = false
        lifecycleScope.launch {
            try {
                val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val ocr = mutableListOf<OcrLine>()
                uris.forEachIndexed { page, u ->
                    val offset = page * 100_000 // paginile una sub alta
                    val res = rec.process(InputImage.fromFilePath(this@MainActivity, u)).await()
                    for (block in res.textBlocks) for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        ocr += OcrLine(line.text, box.left, box.top + offset, box.bottom + offset)
                    }
                }
                val rows = ReceiptParser.buildRows(ocr)
                val text = rows.joinToString("\n")
                if (!ReceiptParser.containsOwnCif(text)) {
                    clearFields()
                    setStatus("CUI ${ReceiptParser.OWN_CIF} NU a fost găsit pe document. Nu se trimite nimic.", false)
                    return@launch
                }
                val d = ReceiptParser.parse(rows)
                field(R.id.etDate).setText(d.date?.format(fmt) ?: "")
                field(R.id.etDoc).setText(d.document)
                field(R.id.etPartner).setText(d.partner)
                spinner.setSelection(ReceiptParser.CATEGORIES.indexOf(d.category).coerceAtLeast(0))
                field(R.id.etAmount).setText(d.amount?.let { "%.2f".format(java.util.Locale.US, it) } ?: "")
                findViewById<RadioButton>(if (d.isIncome) R.id.rbIncasare else R.id.rbPlata).isChecked = true
                findViewById<RadioButton>(if (d.paidByCard) R.id.rbBanca else R.id.rbNumerar).isChecked = true
                setStatus("CUI ${ReceiptParser.OWN_CIF} găsit ✓  Verifică datele și trimite.", true)
                btnSend.isEnabled = true
            } catch (e: Exception) {
                setStatus("Eroare OCR: ${e.message}", false)
            }
        }
    }

    private fun send(url: String) {
        if (!url.startsWith("https://")) { setStatus("Completează URL-ul Apps Script (jos).", false); return }
        val date = runCatching { LocalDate.parse(field(R.id.etDate).text.toString().trim(), fmt) }.getOrNull()
        val amount = field(R.id.etAmount).text.toString().replace(',', '.').toDoubleOrNull()
        if (date == null || amount == null) { setStatus("Data sau suma invalidă.", false); return }
        val income = findViewById<RadioButton>(R.id.rbIncasare).isChecked
        val personal = findViewById<CheckBox>(R.id.cbPersonal).isChecked && !income
        val bank = findViewById<RadioButton>(R.id.rbBanca).isChecked && !personal
        val payload = JSONObject()
            .put("cif", ReceiptParser.OWN_CIF)
            .put("date", date.toString())
            .put("document", field(R.id.etDoc).text.toString())
            .put("partner", field(R.id.etPartner).text.toString())
            .put("category", spinner.selectedItem as String)
            .put("amount", amount)
            .put("column", when { income && bank -> "F"; income -> "G"; bank -> "H"; else -> "I" })
            .put("aport", personal)
        btnSend.isEnabled = false
        setStatus("Trimit…", null)
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { SheetsClient.send(url, payload) }
                val j = runCatching { JSONObject(r) }.getOrNull()
                if (j?.optBoolean("ok") == true) {
                    if (personal && !j.has("aportRow"))
                        setStatus("Plata a fost adăugată (rândul ${j.optInt("row")}), dar scriptul din Google Sheets e vechi și NU a adăugat aportul. Actualizează scriptul (Code.gs) și adaugă aportul manual.", false)
                    else setStatus("Adăugat în Registru (rândul ${j.optInt("row")})" + (if (personal) " + aport titular" else "") + " ✓", true)
                    clearFields()
                }
                else { setStatus("Răspuns neașteptat: $r", false); btnSend.isEnabled = true }
            } catch (e: Exception) {
                setStatus("Eroare la trimitere: ${e.message}", false); btnSend.isEnabled = true
            }
        }
    }

    // ---------- actualizare ----------
    private var latestVersion: String? = null

    private fun currentVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"

    /** Citește versiunea publicată pe GitHub (titlul release-ului: "Contabil Primar v1.3"). */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            latestVersion = withContext(Dispatchers.IO) { UpdateChecker.latestVersion() }
            val btn = findViewById<Button>(R.id.btnUpdate)
            if (latestVersion != null && UpdateChecker.isNewer(latestVersion!!, currentVersion())) {
                btn.text = "Actualizare v$latestVersion"
                btn.setTextColor(Color.rgb(43, 182, 115))
            }
        }
    }

    private fun onUpdateClick() {
        val cur = currentVersion()
        val latest = latestVersion
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        if (latest != null && UpdateChecker.isNewer(latest, cur)) {
            builder.setTitle("Versiune nouă: $latest")
                .setMessage("Ai versiunea $cur. Descarci versiunea $latest și o instalezi peste cea actuală; datele și linkul scriptului rămân salvate.")
                .setPositiveButton("Descarcă") { _, _ -> openDownload() }
                .setNegativeButton("Mai târziu", null)
        } else {
            builder.setTitle("Contabil Primar $cur")
                .setMessage(if (latest == null) "Nu am putut verifica acum dacă există o versiune nouă."
                            else "Ai ultima versiune.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Descarcă oricum") { _, _ -> openDownload() }
        }
        builder.show()
    }

    private fun openDownload() {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(UpdateChecker.DOWNLOAD_URL)))
    }

    // ---------- setări: linkul scriptului ----------
    private val urlRegex = Regex("^https://script\\.google\\.com/(macros|a/macros/[^/]+)/s/[A-Za-z0-9_-]+/exec$")

    private fun scriptUrl(): String = prefs.getString("url", "").orEmpty().trim()

    private fun updateConnectionLabel() {
        val tv = findViewById<TextView>(R.id.tvConn)
        if (scriptUrl().isBlank()) {
            tv.text = "Google Sheets neconfigurat – apasă ⚙️"
            tv.setTextColor(Color.RED)
        } else {
            tv.text = "Conectat la Google Sheets ✓"
            tv.setTextColor(Color.rgb(0, 128, 0))
        }
    }

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val et = v.findViewById<EditText>(R.id.etScriptUrl)
        val tvVerify = v.findViewById<TextView>(R.id.tvVerify)
        et.setText(scriptUrl())
        v.findViewById<Button>(R.id.btnVerify).setOnClickListener {
            val url = et.text.toString().trim()
            if (!urlRegex.matches(url)) { tvVerify.text = "✗ Linkul trebuie să înceapă cu https://script.google.com/ și să se termine cu /exec"; tvVerify.setTextColor(Color.RED); return@setOnClickListener }
            tvVerify.text = "Verific…"; tvVerify.setTextColor(Color.GRAY)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { SheetsClient.get(url) } }
                val ver = r.getOrNull()?.let { runCatching { JSONObject(it).optString("version") }.getOrNull() }
                if (!ver.isNullOrBlank()) { tvVerify.text = "✓ Scriptul răspunde (versiunea $ver)"; tvVerify.setTextColor(Color.rgb(0, 128, 0)) }
                else { tvVerify.text = "✗ Nu răspunde scriptul Contabil Primar: ${r.exceptionOrNull()?.message ?: "răspuns neașteptat"}"; tvVerify.setTextColor(Color.RED) }
            }
        }
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Setări").setView(v)
            .setPositiveButton("Salvează", null)
            .setNegativeButton("Anulează", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = et.text.toString().trim()
                if (!urlRegex.matches(url)) {
                    tvVerify.text = "✗ Link invalid: trebuie să înceapă cu https://script.google.com/ și să se termine cu /exec"
                    tvVerify.setTextColor(Color.RED)
                    return@setOnClickListener
                }
                val save = {
                    prefs.edit().putString("url", url).apply()
                    updateConnectionLabel()
                    dlg.dismiss()
                }
                val old = scriptUrl()
                if (old.isNotBlank() && old != url) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Schimbi linkul scriptului?")
                        .setMessage("Bonurile se vor trimite către noul script. Ești sigur?")
                        .setPositiveButton("Da, schimbă") { _, _ -> save() }
                        .setNegativeButton("Nu", null).show()
                } else save()
            }
        }
        dlg.show()
    }

    private fun showAbout() {
        val v = layoutInflater.inflate(R.layout.dialog_about, null)
        val ver = packageManager.getPackageInfo(packageName, 0).versionName
        v.findViewById<TextView>(R.id.aboutVersion).text = "Versiunea $ver"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(v).setPositiveButton("OK", null).show()
    }

    private fun field(id: Int) = findViewById<EditText>(id)
    private fun clearFields() {
        listOf(R.id.etDate, R.id.etDoc, R.id.etPartner, R.id.etAmount).forEach { field(it).setText("") }
        findViewById<CheckBox>(R.id.cbPersonal).isChecked = false
    }
    private fun setStatus(msg: String, ok: Boolean?) {
        status.text = msg
        status.setTextColor(when (ok) { true -> Color.rgb(0, 128, 0); false -> Color.RED; null -> Color.GRAY })
    }
}
