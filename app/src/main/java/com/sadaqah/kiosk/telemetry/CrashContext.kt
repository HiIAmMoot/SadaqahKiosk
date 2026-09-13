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

    // Same reasoning as settings/affiliateKey above: crashOutbox's onDropped
    // must reach the live Activity's status store without the crash handler
    // itself ever holding an Activity reference. onCreate repoints this on
    // every recreation; a lambda that reads this field is non-capturing.
    @Volatile var onOutboxDropped: ((Int) -> Unit)? = null
}
