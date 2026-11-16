package ro.cretu.bonscanner

import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    const val DOWNLOAD_URL =
        "https://github.com/urs-maro/agents-in-sdlc/releases/download/contabil-primar/ContabilPrimar.apk"
    private const val RELEASE_API =
        "https://api.github.com/repos/urs-maro/agents-in-sdlc/releases/tags/contabil-primar"

    /** Versiunea din titlul release-ului ("Contabil Primar v1.3" -> "1.3"), sau null dacă nu se poate citi. */
    fun latestVersion(): String? = runCatching {
        val c = URL(RELEASE_API).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) return null
        val body = c.inputStream.bufferedReader().readText()
        versionFromRelease(body)
    }.getOrNull()

    fun versionFromRelease(json: String): String? =
        Regex("\"name\"\\s*:\\s*\"[^\"]*?v(\\d+(?:\\.\\d+)*)\"").find(json)?.groupValues?.get(1)

    /** true dacă a > b, comparând numeric pe componente (1.10 > 1.9). */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
