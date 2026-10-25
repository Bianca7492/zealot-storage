package sx.proxies.peer.network

/**
 * The reconnect backoff policy, kept apart from [RelayConnection] so it is pure and JVM-testable.
 *
 * The old policy had a hard terminal cap: after a fixed number of attempts it logged "Giving up"
 * and stopped retrying, which is why bandwidth sharing could stop and never come back after a
 * long outage. The policy here has no terminal state — [delayMillis] grows the wait
 * exponentially up to a ceiling and then holds it there, and there is deliberately no
 * `giveUp` / `isExhausted` method for a caller to consult, so the connection loop cannot
 * accidentally reintroduce a cap.
 *
 * The attempt counter is a backoff input only, never a stop condition.
 */
internal object ReconnectBackoff {

    /** First retry wait. */
    const val BASE_MILLIS: Long = 5_000L

    /** Ceiling the wait grows to and then holds. */
    const val MAX_MILLIS: Long = 120_000L

    /** The shift is clamped here, so the exponent stops doubling once the ceiling is reached. */
    private const val MAX_SHIFT = 5

    /**
     * The wait before retry number [attempt] (1-based: the first retry is attempt 1).
     *
     * 5s, 10s, 20s, 40s, 80s, then held at 2 minutes. Never returns 0 and never fails: a negative
     * or zero [attempt] is treated as the first retry, so a caller cannot accidentally ask for a
     * tight retry loop.
     */
    fun delayMillis(attempt: Int): Long {
        val n = if (attempt < 1) 1 else attempt
        val shift = minOf(n - 1, MAX_SHIFT)
        return minOf(BASE_MILLIS shl shift, MAX_MILLIS)
    }
}
