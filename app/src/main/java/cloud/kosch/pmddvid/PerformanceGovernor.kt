package cloud.kosch.pmddvid

/**
 * Keeps local depth estimation subordinate to the actual camera/encoder path.
 * Android thermal status values are ordered: 0 none, 1 light, 2 moderate, 3+ severe.
 */
data class AnalysisPlan(val intervalNs: Long, val enabled: Boolean)

object PerformanceGovernor {
    private const val BASE_MS = 350L
    private const val MAX_INFERENCE_BACKOFF_MS = 1_500L

    fun plan(lastInferenceMs: Long, thermalStatus: Int): AnalysisPlan {
        val measured =
            if (lastInferenceMs <= 0L) BASE_MS
            else (lastInferenceMs * 3L / 2L).coerceIn(BASE_MS, MAX_INFERENCE_BACKOFF_MS)
        val thermalFloor =
            when {
                thermalStatus >= 3 -> 1_500L
                thermalStatus >= 2 -> 850L
                thermalStatus >= 1 -> 550L
                else -> BASE_MS
            }
        return AnalysisPlan(
            intervalNs = maxOf(measured, thermalFloor) * 1_000_000L,
            // Always permit one bootstrap analysis so PMDD can become ready even if the
            // device already reports severe heat. After that, severe heat pauses ML work.
            enabled = lastInferenceMs <= 0L || thermalStatus < 3,
        )
    }
}
