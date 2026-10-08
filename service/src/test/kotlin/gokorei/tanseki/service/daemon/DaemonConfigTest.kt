package gokorei.tanseki.service.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class DaemonConfigTest {
    @Test
    fun `watch debounce falls back to the default when absent or invalid`() {
        assertEquals(DaemonConfig.DEFAULT_WATCH_DEBOUNCE, DaemonConfig.watchDebounceFromEnv { null })
        assertEquals(DaemonConfig.DEFAULT_WATCH_DEBOUNCE, DaemonConfig.watchDebounceFromEnv { "" })
        assertEquals(DaemonConfig.DEFAULT_WATCH_DEBOUNCE, DaemonConfig.watchDebounceFromEnv { "abc" })
        assertEquals(DaemonConfig.DEFAULT_WATCH_DEBOUNCE, DaemonConfig.watchDebounceFromEnv { "0" })
        assertEquals(DaemonConfig.DEFAULT_WATCH_DEBOUNCE, DaemonConfig.watchDebounceFromEnv { "-5" })
    }

    @Test
    fun `watch debounce parses a positive override`() {
        assertEquals(50.milliseconds, DaemonConfig.watchDebounceFromEnv { "50" })
        assertEquals(1500.milliseconds, DaemonConfig.watchDebounceFromEnv { "1500" })
    }

    @Test
    fun `reconcile interval parses a positive override`() {
        assertEquals(DaemonConfig.DEFAULT_RECONCILE_INTERVAL, DaemonConfig.reconcileIntervalFromEnv { null })
        assertEquals(DaemonConfig.DEFAULT_RECONCILE_INTERVAL, DaemonConfig.reconcileIntervalFromEnv { "0" })
        assertEquals(2500.milliseconds, DaemonConfig.reconcileIntervalFromEnv { "2500" })
    }
}
