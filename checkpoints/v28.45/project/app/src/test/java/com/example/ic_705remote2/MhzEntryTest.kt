package com.example.ic_705remote2
import org.junit.Assert.*
import org.junit.Test
class MhzEntryTest {
    @Test fun parsesMHzExactly() {
        assertEquals(12200000L, MhzEntry.parse("12.200000"))
        assertEquals(12200001L, MhzEntry.parse("12.200001"))
        assertEquals(12000000L, MhzEntry.parse("12"))
        assertEquals(146520000L, MhzEntry.parse("146.520000"))
        assertEquals(12200000L, MhzEntry.parse(" 12,200000 "))
    }
    @Test fun formatsSixPlacesAndRoundTrips() {
        assertEquals("12.200000", MhzEntry.format(12200000L))
        assertEquals("0.001000", MhzEntry.format(1000L))
        for (hz in listOf(1000L, 7074000L, 12200001L, 146520000L, 9999999999L))
            assertEquals(hz, MhzEntry.parse(MhzEntry.format(hz)))
    }
    @Test fun rejectsInvalidAndOldHzInput() {
        for (value in listOf("", "12200000", "12.2000001", "-12", "0", "0.000001", "1e2", "12..2", "NaN"))
            assertNull(value, MhzEntry.parse(value))
    }
}
