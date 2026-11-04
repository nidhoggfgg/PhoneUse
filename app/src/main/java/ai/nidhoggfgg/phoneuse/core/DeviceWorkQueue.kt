package ai.nidhoggfgg.phoneuse.core

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bound time waiting for the device, independently of time spent executing an operation. */
internal class DeviceWorkQueue(private val maxWaitMs: Long = 1_000) {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue(32),
        { task -> Thread(task, "phone-use-device") })
    private val deadlines = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "phone-use-device-queue-deadline") }
        .apply { removeOnCancelPolicy = true }

    fun execute(onExpired: () -> Unit, work: () -> Unit) {
        val claimed = AtomicBoolean()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs)
        lateinit var expiry: ScheduledFuture<*>
        val task = Runnable {
            if (claimed.compareAndSet(false, true)) {
                expiry.cancel(false)
                // Enforce expiry even if the deadline scheduler was delayed.
                if (System.nanoTime() >= deadline) onExpired() else work()
            }
        }
        expiry = deadlines.schedule({
            if (claimed.compareAndSet(false, true)) {
                worker.remove(task)
                onExpired()
            }
        }, maxWaitMs, TimeUnit.MILLISECONDS)
        // The timer is assigned before publishing the task to the worker.
        try { worker.execute(task) }
        catch (e: java.util.concurrent.RejectedExecutionException) {
            claimed.set(true)
            expiry.cancel(false)
            throw e
        }
    }

    fun shutdown() {
        worker.shutdown()
        deadlines.shutdown() // Keep deadlines for already queued reads until they drain/expire.
    }
}
