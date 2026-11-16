package ro.cretu.bonscanner

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    @Test fun parsesReleaseName() {
        val json = """{"tag_name":"contabil-primar","name":"Contabil Primar v1.3","assets":[{"name":"ContabilPrimar.apk"}]}"""
        assertEquals("1.3", UpdateChecker.versionFromRelease(json))
    }

    @Test fun comparesVersions() {
        assertTrue(UpdateChecker.isNewer("1.3", "1.2"))
        assertTrue(UpdateChecker.isNewer("1.10", "1.9"))
        assertFalse(UpdateChecker.isNewer("1.2", "1.2"))
        assertFalse(UpdateChecker.isNewer("1.1", "1.2"))
    }
}
