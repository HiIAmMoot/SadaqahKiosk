package com.sadaqah.kiosk.telemetry

data class GateInputs(
    val enabled: Boolean,
    val configured: Boolean,
    val activated: Boolean,
    val networkAvailable: Boolean,
    val queueDepth: Int,
    val backoffUntilMs: Long
)

enum class FlushBlock {
    NONE,
    DISABLED,
    NOT_CONFIGURED,
    NOT_ACTIVATED,
    NO_NETWORK,
    EMPTY_QUEUE,
    BACKING_OFF
}

/**
 * Decides whether telemetry may be sent right now, and how long to wait after a
 * failure. Pure so the policy can be exercised without a device, which is the
 * same split `recovery/` uses.
 */
object TelemetryGate {
    const val MAX_BACKOFF_MS = 60L * 60 * 1000
    private const val BASE_BACKOFF_MS = 60L * 1000

    /**
     * Checks run most-fundamental first so the reason reported in a log names the
     * real problem: a disabled kiosk should say "disabled", not "no network".
     */
    fun evaluate(inputs: GateInputs, nowMs: Long): FlushBlock = when {
        !inputs.enabled -> FlushBlock.DISABLED
        !inputs.configured -> FlushBlock.NOT_CONFIGURED
        !inputs.activated -> FlushBlock.NOT_ACTIVATED
        !inputs.networkAvailable -> FlushBlock.NO_NETWORK
        inputs.queueDepth <= 0 -> FlushBlock.EMPTY_QUEUE
        nowMs < inputs.backoffUntilMs -> FlushBlock.BACKING_OFF
        else -> FlushBlock.NONE
    }

    /** Exponential from one minute, capped at an hour. Shifting is bounded before
     *  it is applied so a runaway failure count cannot overflow into a negative. */
    fun backoffDelayMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0L
        val steps = minOf(consecutiveFailures - 1, 20)
        val delay = BASE_BACKOFF_MS shl steps
        return minOf(delay, MAX_BACKOFF_MS)
    }
}
