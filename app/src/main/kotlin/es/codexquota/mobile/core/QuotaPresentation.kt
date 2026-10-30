package es.codexquota.mobile.core

/** Shared, Android-independent rules for the app and widgets. */
object QuotaPresentation {
    const val NORMAL = 0
    const val WARNING = 1
    const val CRITICAL = 2

    @JvmStatic
    fun colorLevel(remainingPercent: Double, warningThreshold: Int): Int {
        if (!remainingPercent.isFinite()) return NORMAL
        val threshold = warningThreshold.coerceIn(1, 100)
        return when {
            remainingPercent <= minOf(10, threshold) -> CRITICAL
            remainingPercent <= threshold -> WARNING
            else -> NORMAL
        }
    }

    @JvmStatic
    fun isStale(fetchedAtMillis: Long, nowMillis: Long, intervalMinutes: Int): Boolean =
        RefreshPolicy.isStale(fetchedAtMillis, nowMillis, intervalMinutes)

    /** Information for an existing confirmation; never authorizes or schedules a reset. */
    @JvmStatic
    fun renewalContext(snapshot: QuotaSnapshot?, nowMillis: Long, intervalMinutes: Int): String {
        if (snapshot == null || snapshot.windows.isEmpty()) return ""
        val heading = if (isStale(snapshot.fetchedAtMillis, nowMillis, intervalMinutes)) {
            "Últimos datos guardados; las fechas pueden haber cambiado."
        } else {
            "Según la última consulta:"
        }
        return buildString {
            append(heading)
            append("\nRenovación automática")
            for (window in snapshot.windows) {
                append("\n• ")
                append(window.label)
                append(": ")
                val date = window.resetAtMillis
                append(when {
                    date == null -> "fecha no disponible"
                    date <= nowMillis -> "fecha alcanzada; pendiente de comprobar"
                    else -> RelativeTimes.until(nowMillis, date)
                })
            }
        }
    }
}
