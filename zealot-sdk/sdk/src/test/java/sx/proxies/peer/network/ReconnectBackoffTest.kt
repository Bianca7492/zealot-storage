package sx.proxies.peer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect policy must never stop retrying. This is the regression guard for the reported
 * defect "when it goes off it doesn't automatically come back on": the old connection gave up
 * permanently after a fixed number of attempts, so a long outage (a flight, a tunnel, an
 * overnight dead zone) ended bandwidth sharing for good even though the user still wanted it on.
 */
class ReconnectBackoffTest {

    @Test
    fun `grows exponentially from the base`() {
        assertEquals(5_000L, ReconnectBackoff.delayMillis(1))
        assertEquals(10_000L, ReconnectBackoff.delayMillis(2))
        assertEquals(20_000L, ReconnectBackoff.delayMillis(3))
        assertEquals(40_000L, ReconnectBackoff.delayMillis(4))
        assertEquals(80_000L, ReconnectBackoff.delayMillis(5))
    }

    @Test
    fun `holds at the ceiling instead of overflowing`() {
        // The old code clamped the shift; a naive `base shl attempt` would overflow past attempt
        // 60 and could return a negative delay (a tight, battery-burning retry loop).
        assertEquals(ReconnectBackoff.MAX_MILLIS, ReconnectBackoff.delayMillis(6))
        assertEquals(ReconnectBackoff.MAX_MILLIS, ReconnectBackoff.delayMillis(7))
        assertEquals(ReconnectBackoff.MAX_MILLIS, ReconnectBackoff.delayMillis(100))
        assertEquals(ReconnectBackoff.MAX_MILLIS, ReconnectBackoff.delayMillis(Int.MAX_VALUE))
    }

    @Test
    fun `keeps returning a sane wait however long the outage lasts`() {
        // There is no terminal attempt: a very long outage still yields a bounded, positive wait,
        // which is what lets the reconnect loop run forever without a cap.
        for (attempt in intArrayOf(50, 500, 5_000, 500_000)) {
            val delay = ReconnectBackoff.delayMillis(attempt)
            assertTrue("delay must stay positive at attempt $attempt", delay > 0)
            assertEquals(
                "delay must stay at the ceiling at attempt $attempt",
                ReconnectBackoff.MAX_MILLIS,
                delay,
            )
        }
    }

    @Test
    fun `a nonsense attempt is treated as the first retry, never a tight loop`() {
        assertEquals(ReconnectBackoff.BASE_MILLIS, ReconnectBackoff.delayMillis(0))
        assertEquals(ReconnectBackoff.BASE_MILLIS, ReconnectBackoff.delayMillis(-5))
    }
}
