package ro.cretu.bonscanner

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {
    private val bon = """
        DEDEMAN SRL
        CIF: RO2816464
        CIF CLIENT: RO 5555 7417
        DATA: 14.11.2026 ORA 12:31
        SURUBURI 2 x 5,50   11,00
        TOTAL      1.234,56
        CARD       1.234,56
        BF: 0042
    """.trimIndent()

    @Test fun cif() {
        assertTrue(ReceiptParser.containsOwnCif(bon))
        assertFalse(ReceiptParser.containsOwnCif("CIF 155557417"))
        assertFalse(ReceiptParser.containsOwnCif("CIF 12345678"))
    }

    @Test fun parseBon() {
        val d = ReceiptParser.parse(bon)
        assertEquals(LocalDate.of(2026, 11, 14), d.date)
        assertEquals(1234.56, d.amount!!, 0.001)
        assertEquals("DEDEMAN SRL - Suruburi", d.partner)
        assertEquals("Bon fiscal 0042", d.document)
        assertTrue(d.paidByCard)
        assertFalse(d.isIncome)
    }

    @Test fun invoiceIncome() {
        val f = "FACTURA FISCALA Seria CIF nr. 003\nFurnizor: CRETU I.P. IONUT II\nCIF 55557417\nCumparator: CLIENT SRL\nData: 02.12.2026\nTotal de plata 13.348,00"
        val d = ReceiptParser.parse(f)
        assertTrue(d.isIncome)
        assertEquals("Factura CIF 003", d.document)
        assertEquals(13348.0, d.amount!!, 0.001)
        assertEquals("CLIENT SRL", d.partner.removePrefix("Cumparator: "))
    }

    /** Bonul Petrom fotografiat, cu textul împărțit pe coloane cum îl dă ML Kit. */
    @Test fun petromColumns() {
        fun l(t: String, x: Int, y: Int) = OcrLine(t, x, y, y + 30)
        val ocr = listOf(
            l("S.C. OMV PETROM MARKETING S.R.L.", 280, 170), l("PETROM 9 CAMPULUNG", 390, 210),
            l("STR. CUZA VODA NR.38A", 370, 250), l("MUN. CAMPULUNG , JUD. ARGES", 320, 285),
            l("C.I.F.: RO11201891", 410, 325), l("BON FISCAL", 480, 365),
            l("NUMAR POS:", 210, 420), l("2", 915, 395),
            l("NUMAR TRANZACTIE:", 210, 455), l("1110403/2/261104/111", 570, 440),
            l("* 4 MOTORINA STANDARD", 170, 525),
            l("41,8799 L X 10,91", 380, 560), l("456,91 A", 825, 555),
            l("TOTAL:", 170, 615), l("456,91", 860, 590),
            l("CARTE CREDIT", 170, 650), l("456,91", 860, 630),
            l("CLIENT C.U.I./C.I.F.:", 200, 685), l("55557417", 790, 670),
            l("NUME CLIENT:", 200, 730), l("CRETU I.P. IONUT INTREPRINDERE", 360, 760), l("INDIVIDUALA", 740, 795),
            l("MASTERCARD - CONTACTLESS", 195, 890),
            l("TID:0R02733B", 190, 975), l("2026.11.04 08:31:31", 570, 965),
            l("5055", 175, 1620),
            l("TVA", 135, 1665), l("VALOARE", 490, 1665), l("TOTAL", 920, 1670),
            l("A-21,00%", 130, 1710), l("79,30", 525, 1710), l("456,91", 900, 1725),
            l("TOTAL TAXE:", 125, 1755), l("79,30", 525, 1755),
            l("CASIER:", 125, 1800), l("POPA ANCA MIHAELA 002", 585, 1815),
            l("04.11.2026 08:31:37", 120, 1860), l("3042-00096", 820, 1870),
        )
        val rows = ReceiptParser.buildRows(ocr.shuffled(java.util.Random(1)))
        val text = rows.joinToString("\n")
        assertTrue(ReceiptParser.containsOwnCif(text))
        val d = ReceiptParser.parse(rows)
        assertEquals(LocalDate.of(2026, 11, 4), d.date)
        assertEquals(456.91, d.amount!!, 0.001)
        assertEquals("Bon fiscal 1110403/2/261104/111", d.document)
        assertEquals("OMV PETROM MARKETING SRL - Motorina standard", d.partner)
        assertEquals("Combustibil", d.category)
        assertTrue(d.paidByCard)
        assertFalse(d.isIncome)
    }

    @Test fun transactionNumberOnSeparateRow() {
        val rows = listOf("S.C. OMV PETROM MARKETING S.R.L.", "BON FISCAL", "1110403/2/261104/111", "NUMAR TRANZACTIE:", "* 4 MOTORINA STANDARD", "TOTAL:   456,91")
        assertEquals("Bon fiscal 1110403/2/261104/111", ReceiptParser.parse(rows).document)
    }
}
