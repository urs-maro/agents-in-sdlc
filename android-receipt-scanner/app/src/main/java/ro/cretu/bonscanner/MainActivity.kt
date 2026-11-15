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
        findViewById<ImageView>(R.id.preview).setImageURI(pages.first().imageUri)
        recognize(pages.map { it.imageUri })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        btnSend = findViewById(R.id.btnSend)
        val etUrl = findViewById<EditText>(R.id.etUrl)
        etUrl.setText(prefs.getString("url", ""))
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ReceiptParser.CATEGORIES)
        spinner.setSelection(ReceiptParser.CATEGORIES.indexOf("Altele"))

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            scanner.getStartScanIntent(this)
                .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { setStatus("Scanner indisponibil: ${it.message}", false) }
        }
        btnSend.setOnClickListener {
            val url = etUrl.text.toString().trim()
            prefs.edit().putString("url", url).apply()
            send(url)
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
        val bank = findViewById<RadioButton>(R.id.rbBanca).isChecked
        val payload = JSONObject()
            .put("cif", ReceiptParser.OWN_CIF)
            .put("date", date.toString())
            .put("document", field(R.id.etDoc).text.toString())
            .put("partner", field(R.id.etPartner).text.toString())
            .put("category", spinner.selectedItem as String)
            .put("amount", amount)
            .put("column", when { income && bank -> "F"; income -> "G"; bank -> "H"; else -> "I" })
        btnSend.isEnabled = false
        setStatus("Trimit…", null)
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { SheetsClient.send(url, payload) }
                val j = runCatching { JSONObject(r) }.getOrNull()
                if (j?.optBoolean("ok") == true) { setStatus("Adăugat în Registru (rândul ${j.optInt("row")}) ✓", true); clearFields() }
                else { setStatus("Răspuns neașteptat: $r", false); btnSend.isEnabled = true }
            } catch (e: Exception) {
                setStatus("Eroare la trimitere: ${e.message}", false); btnSend.isEnabled = true
            }
        }
    }

    private fun field(id: Int) = findViewById<EditText>(id)
    private fun clearFields() = listOf(R.id.etDate, R.id.etDoc, R.id.etPartner, R.id.etAmount).forEach { field(it).setText("") }
    private fun setStatus(msg: String, ok: Boolean?) {
        status.text = msg
        status.setTextColor(when (ok) { true -> Color.rgb(0, 128, 0); false -> Color.RED; null -> Color.GRAY })
    }
}
