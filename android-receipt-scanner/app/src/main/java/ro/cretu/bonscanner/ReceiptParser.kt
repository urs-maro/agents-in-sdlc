package ro.cretu.bonscanner

import java.time.LocalDate

data class ReceiptData(
    val date: LocalDate?,
    val document: String,
    val partner: String,
    val category: String,
    val amount: Double?,
    val isIncome: Boolean,
    val paidByCard: Boolean,
)

/** Un fragment de text recunoscut, cu poziția lui pe pagină (pixeli). */
data class OcrLine(val text: String, val left: Int, val top: Int, val bottom: Int)

object ReceiptParser {
    const val OWN_CIF = "55557417"

    /** Categoriile din lista de validare a coloanei E din foaia "Registru". */
    val CATEGORIES = listOf(
        "Încasare servicii IT", "Taxe și impozite", "Contabilitate",
        "Abonamente software", "Echipamente", "Utilități", "Altele",
    )

    // ---------- reconstruirea rândurilor după poziție ----------

    /**
     * ML Kit returnează textul în blocuri (de multe ori pe coloane): "TOTAL:" și "456,91"
     * ajung în blocuri diferite. Le regrupăm pe rânduri vizuale după poziția verticală.
     */
    fun buildRows(lines: List<OcrLine>): List<String> {
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { (it.top + it.bottom) / 2 }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (l in sorted) {
            val c = (l.top + l.bottom) / 2
            val row = rows.lastOrNull()
            val ref = row?.first()
            if (ref != null && kotlin.math.abs(c - (ref.top + ref.bottom) / 2) <= maxOf(ref.bottom - ref.top, l.bottom - l.top) * 0.6) {
                row.add(l)
            } else rows.add(mutableListOf(l))
        }
        return rows.map { r -> r.sortedBy { it.left }.joinToString("   ") { it.text.trim() } }
    }

    // ---------- CUI ----------

    private val cifRegex = Regex(
        "(?<![0-9])" + OWN_CIF.map { c ->
            when (c) { '5' -> "[5S]"; '1' -> "[1IL|]"; '0' -> "[0O]"; else -> c.toString() }
        }.joinToString("[\\s.\\-]?") + "(?![0-9])"
    )

    fun containsOwnCif(text: String): Boolean = cifRegex.containsMatchIn(text.uppercase())

    // ---------- data ----------

    private const val SEP = "\\s*[./\\-]\\s*"
    private val dateRegexes = listOf(
        Regex("(?<!\\d)(\\d{1,2})$SEP(\\d{1,2})$SEP(\\d{4})(?!\\d)") to "dmy",
        Regex("(?<!\\d)(\\d{4})$SEP(\\d{1,2})$SEP(\\d{1,2})(?!\\d)") to "ymd",
        Regex("(?<!\\d)(\\d{1,2})$SEP(\\d{1,2})$SEP(\\d{2})(?![\\d.,])") to "dmy2",
    )

    fun parseDate(text: String): LocalDate? {
        // cifre citite greșit de OCR (O→0) doar în interiorul grupurilor numerice
        val t = text.replace(Regex("(?<=\\d)[Oo](?=\\d)"), "0")
        val found = mutableListOf<LocalDate>()
        for ((re, kind) in dateRegexes) {
            for (m in re.findAll(t)) {
                val (a, b, c) = m.destructured
                runCatching {
                    when (kind) {
                        "dmy" -> LocalDate.of(c.toInt(), b.toInt(), a.toInt())
                        "ymd" -> LocalDate.of(a.toInt(), b.toInt(), c.toInt())
                        else -> LocalDate.of(2000 + c.toInt(), b.toInt(), a.toInt())
                    }
                }.getOrNull()?.takeIf { it.year in 2015..2100 }?.let { found += it }
            }
        }
        // data care apare de cele mai multe ori (pe bon apare de obicei de 2-3 ori)
        return found.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    // ---------- sume ----------

    private val amountRe = Regex("(?<![\\d.,%])(\\d{1,3}(?:[ .]\\d{3})+|\\d+)\\s?[.,]\\s?(\\d{2})(?![\\d%])(?!\\s*%)")

    fun amountsIn(line: String): List<Double> = amountRe.findAll(line).mapNotNull {
        (it.groupValues[1].replace(" ", "").replace(".", "") + "." + it.groupValues[2]).toDoubleOrNull()
    }.toList()

    private fun isTaxLine(up: String) =
        up.contains("TVA") || up.contains("TAXE") || up.contains("%") || up.contains("SUBTOTAL") || up.contains("REST")

    fun parseAmount(rows: List<String>): Double? {
        val keys = listOf("TOTAL DE PLATA", "TOTAL DE PLATĂ", "TOTAL PLATA", "TOTAL GENERAL", "TOTAL LEI", "TOTAL")
        for (key in keys) {
            for ((i, r) in rows.withIndex()) {
                val up = r.uppercase()
                if (!up.contains(key) || isTaxLine(up)) continue
                val a = amountsIn(r).maxOrNull()
                    ?: rows.getOrNull(i + 1)?.takeIf { !isTaxLine(it.uppercase()) }?.let { amountsIn(it).maxOrNull() }
                    ?: rows.getOrNull(i - 1)?.takeIf { !isTaxLine(it.uppercase()) }?.let { amountsIn(it).maxOrNull() }
                if (a != null) return a
            }
        }
        // rezervă: suma care se repetă cel mai des (totalul apare la total, plată, card...), apoi cea mai mare
        val all = rows.filterNot { isTaxLine(it.uppercase()) }.flatMap { amountsIn(it) }
        return all.groupingBy { it }.eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<Double, Int>> { it.value }.thenBy { it.key })?.key
    }

    // ---------- document ----------

    fun parseDocument(text: String, isInvoice: Boolean): String {
        val factura = Regex("(?i)factur[aă](?:\\s+fiscal[aă])?.{0,40}?(?:seria\\s*([A-Z]{1,6})\\s*)?(?:nr\\.?|num[aă]r)\\s*[:.]?\\s*([A-Z]{0,6}\\s?\\d+)")
            .find(text)
        if (isInvoice && factura != null) {
            return "Factura " + listOf(factura.groupValues[1], factura.groupValues[2]).filter { it.isNotBlank() }.joinToString(" ")
        }
        val bonPatterns = listOf(
            "(?i)(?:nr\\.?\\s*bon|num[aă]r\\s*bon|bon\\s*(?:fiscal)?\\s*nr\\.?)\\s*[:.]?\\s*(\\d+)",
            "(?i)\\bBF\\s*[:.]?\\s*(\\d+)",
            "(?i)num[aă]r\\s*tranzac[tț]ie\\s*[:.]?\\s*([\\d/\\-]+)",
            "(?i)\\bnr\\.?\\s*doc(?:ument)?\\s*[:.]?\\s*(\\d+)",
        )
        for (p in bonPatterns) Regex(p).find(text)?.let { return "Bon fiscal " + it.groupValues[1] }
        return if (isInvoice) "Factura" else "Bon fiscal"
    }

    // ---------- partener ----------

    private val companyRe = Regex("(?i)(?<![A-Z])(S\\.?\\s?R\\.?\\s?L\\.?|S\\.?\\s?A\\.?|PFA|I\\.?\\s?I\\.?|I\\.?\\s?F\\.?|SRL-D|SCS|SNC)(?![A-Z])")
    private fun isOwn(s: String) = s.uppercase().let { it.contains("CRETU") || it.contains("CREȚU") || containsOwnCif(it) }

    fun cleanCompany(s: String): String = s.trim()
        .replace(Regex("^(?i)S\\.?\\s?C\\.?\\s+"), "")
        .replace(Regex("(?i)\\bS\\.?\\s?R\\.?\\s?L\\.?$"), "SRL")
        .replace(Regex("(?i)\\bS\\.\\s?A\\.?$"), "SA")
        .replace(Regex("\\s+"), " ")

    fun parsePartner(rows: List<String>, isIncome: Boolean): String {
        if (isIncome) {
            // factura emisă de noi: partenerul este clientul
            val i = rows.indexOfFirst { Regex("(?i)cump[aă]r[aă]tor|client|beneficiar").containsMatchIn(it) }
            if (i >= 0) rows.drop(i).take(4).firstOrNull { companyRe.containsMatchIn(it) && !isOwn(it) }?.let {
                return cleanCompany(it.substringAfter(':'))
            }
        }
        val name = rows.take(8).firstOrNull { companyRe.containsMatchIn(it) && !isOwn(it) }
            ?: rows.firstOrNull { r -> r.count(Char::isLetter) >= 4 && !isOwn(r) }
            ?: ""
        return cleanCompany(name)
    }

    // ---------- explicație (primul produs) ----------

    fun firstItem(rows: List<String>): String? {
        val stop = Regex("(?i)^\\s*(TOTAL|SUBTOTAL|TVA|CARTE|CARD|NUMERAR|REST)")
        val marker = rows.indexOfFirst { Regex("(?i)BON FISCAL|NUMAR TRANZ|NR\\.? BON").containsMatchIn(it) }
        val header = rows.take(12).indexOfLast { Regex("(?i)C\\.?\\s?I\\.?\\s?F|C\\.?U\\.?I").containsMatchIn(it) }
        val dateRe = Regex("\\d{1,4}\\s*[./\\-]\\s*\\d{1,2}\\s*[./\\-]\\s*\\d{2,4}")
        for (r in rows.drop(maxOf(marker, header) + 1)) {
            if (dateRe.containsMatchIn(r)) continue
            if (stop.containsMatchIn(r)) break
            val name = r.replace(Regex("^[*#\\s\\d]+"), "").replace(Regex("(?i)\\b\\d+([.,]\\d+)?\\s*(L|KG|BUC)?\\s*X\\s*"), "").replace(amountRe, "").replace(Regex("\\s+[A-E]$"), "")
                .replace(Regex("\\s+"), " ").trim()
            if (name.count(Char::isLetter) >= 4 && !Regex("(?i)NUMAR|POS|CASA|CIF|COD").containsMatchIn(name)) {
                return name.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        return null
    }

    // ---------- categorie ----------

    fun guessCategory(text: String, isIncome: Boolean): String {
        if (isIncome) return "Încasare servicii IT"
        val up = text.uppercase()
        fun any(vararg k: String) = k.any { up.contains(it) }
        return when {
            any("ANAF", "IMPOZIT", "TAXA ", "CAS ", "CASS", "TREZORERIE") -> "Taxe și impozite"
            any("CONTABIL", "EXPERT CONTABIL") -> "Contabilitate"
            any("ABONAMENT", "SUBSCRIPTION", "LICENTA", "LICENȚĂ", "SOFTWARE", "CLOUD", "GOOGLE", "MICROSOFT", "ADOBE", "GITHUB") -> "Abonamente software"
            any("ENEL", "E.ON", "EON ", "ELECTRICA", "ENGIE", "DIGI", "RCS", "ORANGE", "VODAFONE", "TELEKOM", "APA NOVA", "ENERGIE", "GAZE", "INTERNET") -> "Utilități"
            any("LAPTOP", "MONITOR", "CALCULATOR", "IMPRIMANT", "TELEFON", "TASTATUR", "MOUSE", "EMAG", "ALTEX", "FLANCO", "DANTE INTERNATIONAL") -> "Echipamente"
            else -> "Altele"
        }
    }

    // ---------- venit / plată ----------

    fun isIncome(text: String): Boolean {
        val up = text.uppercase()
        val cif = cifRegex.find(up)?.range?.first ?: -1
        val buyer = Regex("CUMP[AĂ]R[AĂ]TOR|CLIENT|BENEFICIAR").find(up)?.range?.first ?: -1
        return cif >= 0 && buyer >= 0 && cif < buyer &&
            Regex("FURNIZOR|PRESTATOR|EMITENT").containsMatchIn(up.substring(0, cif))
    }

    fun parse(rows: List<String>): ReceiptData {
        val text = rows.joinToString("\n")
        val up = text.uppercase()
        val isInvoice = up.contains("FACTUR")
        val income = isIncome(text)
        val partner = parsePartner(rows, income)
        val item = if (!isInvoice) firstItem(rows) else null
        return ReceiptData(
            date = parseDate(text),
            document = parseDocument(text, isInvoice),
            partner = listOfNotNull(partner.ifBlank { null }, item).joinToString(" - "),
            category = guessCategory(text, income),
            amount = parseAmount(rows),
            isIncome = income,
            paidByCard = Regex("\\bCARD\\b|CARTE CREDIT|CARTE DEBIT|VISA|MASTERCARD|MAESTRO|CONTACTLESS|ORDIN DE PLAT|OP\\s*NR")
                .containsMatchIn(up) || (isInvoice && !up.contains("NUMERAR")),
        )
    }

    fun parse(text: String): ReceiptData = parse(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
}
