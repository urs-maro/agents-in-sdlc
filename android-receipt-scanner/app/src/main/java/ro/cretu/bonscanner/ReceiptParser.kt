package ro.cretu.bonscanner

import java.time.LocalDate

data class ReceiptData(
    val date: LocalDate?,
    val document: String,
    val partner: String,
    val amount: Double?,
    val isIncome: Boolean,
    val paidByCard: Boolean,
)

object ReceiptParser {
    const val OWN_CIF = "55557417"

    /** CIF-ul poate apărea ca "RO55557417", "RO 5555 7417", "CUI: 55557417" etc. OCR confundă uneori O/0 etc. */
    // fiecare cifră acceptă confuziile tipice OCR (S→5, I/L→1), cu spații/puncte/cratime între cifre
    private val cifRegex = Regex(
        "(?<![0-9])" + OWN_CIF.map { c ->
            when (c) { '5' -> "[5S]"; '1' -> "[1IL|]"; '0' -> "[0O]"; else -> c.toString() }
        }.joinToString("[\\s.\\-]?") + "(?![0-9])"
    )

    fun containsOwnCif(text: String): Boolean = cifRegex.containsMatchIn(text.uppercase())

    private val dateRegexes = listOf(
        Regex("(?<!\\d)(\\d{1,2})[./\\-](\\d{1,2})[./\\-](\\d{4})(?!\\d)") to "dmy",
        Regex("(?<!\\d)(\\d{4})[./\\-](\\d{1,2})[./\\-](\\d{1,2})(?!\\d)") to "ymd",
        Regex("(?<!\\d)(\\d{1,2})[./\\-](\\d{1,2})[./\\-](\\d{2})(?!\\d)") to "dmy2",
    )

    fun parseDate(text: String): LocalDate? {
        for ((re, kind) in dateRegexes) {
            for (m in re.findAll(text)) {
                val (a, b, c) = m.destructured
                val d = runCatching {
                    when (kind) {
                        "dmy" -> LocalDate.of(c.toInt(), b.toInt(), a.toInt())
                        "ymd" -> LocalDate.of(a.toInt(), b.toInt(), c.toInt())
                        else -> LocalDate.of(2000 + c.toInt(), b.toInt(), a.toInt())
                    }
                }.getOrNull()
                if (d != null && d.year in 2000..2100) return d
            }
        }
        return null
    }

    private val amountRe = Regex("(?<![\\d.,])(\\d{1,3}(?:[ .]\\d{3})*|\\d+)[.,](\\d{2})(?!\\d)")

    private fun amountsIn(line: String): List<Double> = amountRe.findAll(line).mapNotNull {
        (it.groupValues[1].replace(" ", "").replace(".", "") + "." + it.groupValues[2]).toDoubleOrNull()
    }.toList()

    fun parseAmount(lines: List<String>): Double? {
        val totalKeys = listOf("TOTAL DE PLATA", "TOTAL DE PLATĂ", "TOTAL PLATA", "TOTAL GENERAL", "TOTAL LEI", "TOTAL")
        for (key in totalKeys) {
            for ((i, l) in lines.withIndex()) {
                val up = l.uppercase()
                if (up.contains(key) && !up.contains("SUBTOTAL") && !up.contains("TVA")) {
                    val a = amountsIn(l).maxOrNull() ?: lines.getOrNull(i + 1)?.let { amountsIn(it).maxOrNull() }
                    if (a != null) return a
                }
            }
        }
        return lines.flatMap { amountsIn(it) }.maxOrNull()
    }

    fun parseDocument(text: String, isInvoice: Boolean): String {
        val factura = Regex("(?i)factur[aă](?:\\s+fiscal[aă])?.{0,40}?(?:seria\\s*([A-Z]{1,6})\\s*)?(?:nr\\.?|num[aă]r)\\s*[:.]?\\s*([A-Z]{0,6}\\s?\\d+)")
            .find(text)
        if (factura != null) {
            val serie = factura.groupValues[1]
            return "Factura " + listOf(serie, factura.groupValues[2]).filter { it.isNotBlank() }.joinToString(" ")
        }
        val bon = Regex("(?i)(?:nr\\.?\\s*bon|bon\\s*(?:fiscal)?\\s*nr\\.?|\\bBF)\\s*[:.]?\\s*(\\d+)").find(text)
        if (bon != null) return "Bon fiscal " + bon.groupValues[1]
        return if (isInvoice) "Factura" else "Bon fiscal"
    }

    fun parsePartner(lines: List<String>): String {
        val company = Regex("(?i)\\b(S\\.?R\\.?L\\.?|S\\.?A\\.?|PFA|I\\.?I\\.?|I\\.?F\\.?|SRL-D)\\b")
        val candidates = lines.take(12).filter { company.containsMatchIn(it) && !it.uppercase().contains("CREȚU") && !it.uppercase().contains("CRETU") }
        return (candidates.firstOrNull() ?: lines.firstOrNull { it.any(Char::isLetter) } ?: "").trim()
    }

    /** Venit = documentul e emis DE noi: CIF-ul nostru apare înaintea secțiunii Client/Cumpărător. */
    fun isIncome(text: String): Boolean {
        val up = text.uppercase()
        val cif = cifRegex.find(up)?.range?.first ?: -1
        val buyer = Regex("CUMP[AĂ]R[AĂ]TOR|CLIENT|BENEFICIAR").find(up)?.range?.first ?: -1
        return cif >= 0 && buyer >= 0 && cif < buyer && Regex("FURNIZOR|PRESTATOR|EMITENT").containsMatchIn(up.substring(0, cif))
    }

    fun parse(text: String): ReceiptData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val up = text.uppercase()
        val isInvoice = up.contains("FACTUR")
        return ReceiptData(
            date = parseDate(text),
            document = parseDocument(text, isInvoice),
            partner = parsePartner(lines),
            amount = parseAmount(lines),
            isIncome = isIncome(text),
            paidByCard = Regex("\\bCARD\\b|VISA|MASTERCARD|\\bPOS\\b|ORDIN DE PLAT|OP\\s*NR").containsMatchIn(up) ||
                (isInvoice && !up.contains("NUMERAR")),
        )
    }
}
