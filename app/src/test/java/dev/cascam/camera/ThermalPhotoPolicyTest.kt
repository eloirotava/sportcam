package dev.cascam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalPhotoPolicyTest {
    @Test fun `thermal policy never shortens the configured interval`() {
        assertEquals(10_000L, ThermalPhotoPolicy.effectiveIntervalMillis(10_000L, ThermalPressure.MODERATE))
        assertEquals(10_000L, ThermalPhotoPolicy.effectiveIntervalMillis(10_000L, ThermalPressure.CRITICAL))
    }

    @Test fun `thermal policy progressively slows frequent photos`() {
        assertEquals(1_000L, ThermalPhotoPolicy.effectiveIntervalMillis(1_000L, ThermalPressure.NORMAL))
        assertEquals(2_000L, ThermalPhotoPolicy.effectiveIntervalMillis(1_000L, ThermalPressure.MODERATE))
        assertEquals(5_000L, ThermalPhotoPolicy.effectiveIntervalMillis(1_000L, ThermalPressure.SEVERE))
        assertEquals(10_000L, ThermalPhotoPolicy.effectiveIntervalMillis(1_000L, ThermalPressure.CRITICAL))
    }
}
