package sx.proxies.peer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A model of the reconnect storm, runnable on the JVM.
 *
 * This is deliberately a MODEL of [RelayConnection]'s control flow, not a test of the class
 * itself: the callbacks that drive it come from OkHttp's WebSocket dispatcher and a real relay
 * socket, neither of which exists in a unit test. What it reproduces exactly is the shape that
 * broke in the field — `connect()` closes the socket it is replacing, OkHttp reports that close on
 * the OLD listener, and that listener's disconnect path schedules another reconnect.
 *
 * The two versions differ only in whether a superseded socket's callback is ignored:
 *
 *   - unguarded (the shipped 1.4.0 behaviour): every reconnect closes a socket, whose callback
 *     schedules another reconnect, whose connect() closes a socket... the number of live reconnect
 *     loops grows without bound. The device stays registered but never holds a tunnel.
 *   - guarded (this fix): a callback from a superseded session does nothing, so there is exactly
 *     one reconnect loop no matter how many triggers fire.
 */
class ReconnectStormModelTest {

    /**
     * The reconnect bookkeeping of one connection, reduced to what matters: a generation counter,
     * the reconnect latch, and whether a callback is allowed to act.
     */
    private class Model(private val guarded: Boolean) {
        var generation = 0
            private set
        private var isReconnecting = false
        private var currentGen: Int? = null

        /** Reconnect loops scheduled over the model's lifetime. One means healthy. */
        var scheduledLoops = 0
            private set

        fun connect() {
            val gen = ++generation
            // Both versions reset the latch here; that is what defeated the old guard.
            isReconnecting = false
            // Closing the previous socket delivers its callback synchronously in this model,
            // which is the ordering OkHttp uses when a socket is closed locally.
            currentGen?.let { onTerminated(it) }
            currentGen = gen
        }

        /** A socket ended: the callback carries the generation of the socket that ended. */
        fun onTerminated(callbackGen: Int) {
            if (guarded && callbackGen != generation) return
            if (!isReconnecting) {
                isReconnecting = true
                scheduledLoops++
            }
        }

        /** A reconnect loop firing: it dials again. */
        fun reconnectLoopFires() = connect()
    }

    @Test
    fun `without the generation guard the reconnect loops grow without bound`() {
        val model = Model(guarded = false)
        model.connect()
        // Ten natural drops, each followed by the reconnect loop firing, is an ordinary outage.
        repeat(10) {
            model.onTerminated(model.generation)
            model.reconnectLoopFires()
        }
        // Every cycle schedules at least one extra loop, so the count runs away.
        assertTrue(
            "the unguarded flow must exhibit runaway loop growth (got ${model.scheduledLoops})",
            model.scheduledLoops > 10,
        )
    }

    @Test
    fun `with the generation guard exactly one loop is ever scheduled per drop`() {
        val model = Model(guarded = true)
        model.connect()
        repeat(10) {
            model.onTerminated(model.generation)
            model.reconnectLoopFires()
        }
        // One scheduled loop per natural drop, and the stale callbacks contributed nothing.
        assertEquals("a guarded flow must not stack loops", 10, model.scheduledLoops)
    }

    @Test
    fun `a stale callback cannot schedule anything`() {
        val model = Model(guarded = true)
        model.connect()
        val before = model.scheduledLoops
        // The close of a socket two generations back, delivered late.
        model.onTerminated(model.generation - 1)
        assertEquals("a superseded callback must be inert", before, model.scheduledLoops)
    }
}
