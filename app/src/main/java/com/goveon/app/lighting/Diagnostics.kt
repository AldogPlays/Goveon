package com.goveon.app.lighting

import android.util.Log

/** Local Logcat only. Never log keys, device addresses, names, or command payloads. */
internal object Diagnostics {
    fun detail(operation: String, message: String) {
        Log.d("Goveon", "$operation: ${safeDiagnosticText(message)}")
    }
    fun command(result: CommandResult) {
        val detail = "${result.transport ?: "Control"} ${result.status}: ${safeDiagnosticText(result.message)}"
        if (result.successful) Log.d("Goveon", detail) else Log.w("Goveon", detail)
    }
    fun failure(operation: String, error: Exception) {
        Log.w("Goveon", "$operation: ${error.javaClass.simpleName}: ${safeDiagnosticText(error.message.orEmpty())}")
    }
}

internal fun safeDiagnosticText(message: String): String = message
    .replace(Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"), "[device]")
    .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "[network]")
    .replace(Regex("(?i)(?:Govee-API-Key|api[_-]?key|authorization)\\s*[:=]\\s*[^\\s,;]+"), "[credential]")
    .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
    .replace(Regex("(?:gh[pousr]_[A-Za-z0-9]+|github_pat_[A-Za-z0-9_]+)"), "[credential]")
    .take(1000)
