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
        assertEquals("DEDEMAN SRL", d.partner)
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
}
