package ai.nidhoggfgg.phoneuse.core

/** Invalidates pending work when Android destroys the service. */
class ControlGate {
    @Volatile var stopped = false
        private set
    @Volatile var generation = 0L
        private set

    @Synchronized fun check(expectedGeneration: Long? = null) {
        if (stopped) fail("CANCELLED", "Service is stopped")
        if (expectedGeneration != null && expectedGeneration != generation)
            fail("CANCELLED", "Request was invalidated")
    }
    @Synchronized fun stop() { stopped = true; generation++ }
}
