package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings

/**
 * What the crash handler reads, held at process scope rather than on the
 * Activity.
 *
 * The uncaught-exception handler is process-global and installed once, but
 * `MainActivity` declares no `android:configChanges` and is recreated on any
 * configuration change. A handler holding the first Activity would read a dead
 * instance from then on — so an operator who turned analytics off would still
 * get a crash row. Plain @Volatile rather than Compose snapshot state because a
 * snapshot read from a dying thread could block on the snapshot lock, and a
 * blocked crash handler is the hung kiosk the chain exists to prevent.
 */
object CrashContext {
    @Volatile var settings: Settings? = null
    @Volatile var affiliateKey: String? = null

    // Holds a bound reference to MainActivity.onOutboxDropped, repointed on
    // every onCreate. The handler retains one live Activity instance by design;
    // the indirection exists so the reference cannot go stale across a
    // configuration change. A lambda that reads this slot is non-capturing.
    // Do not clear this in onDestroy: during overlapping recreation the new
    // instance sets the slot before the old instance is destroyed, so a null
    // would eliminate the live handler mid-chain.
    @Volatile var onOutboxDropped: ((Int) -> Unit)? = null
}
