package ro.cretu.bonscanner

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Trimite rândul către web app-ul Apps Script, care îl inserează cronologic în foaia "Registru". */
object SheetsClient {
    fun send(url: String, payload: JSONObject): String {
        var target = url
        repeat(5) {
            val c = URL(target).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000; c.readTimeout = 30000
            if (target == url) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
            }
            val code = c.responseCode
            if (code in 300..399) { target = c.getHeaderField("Location"); c.disconnect(); return@repeat }
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code >= 400) throw RuntimeException("HTTP $code: $body")
            return body
        }
        throw RuntimeException("Prea multe redirecționări")
    }
}
