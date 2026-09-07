/*
 * RadioChat APRS — Android APRS client for KISS BLE radios,
 * DireWolf (KISS TCP) and APRS-IS.
 * Copyright (C) 2026 Luis Almonte (HI3LAG)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aprs.radiochat.data.aprsis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AprsIsFilterTest {

    @Test
    fun build_withPosition_includesRangeAndMessages() {
        val f = AprsIsFilter.build("HI3LAG-7", 80, 18.4861, -69.9312)
        assertTrue(f.contains("r/18.49/-69.93/80"))
        assertTrue(f.contains("m/80"))
        assertTrue(f.contains("g/HI3LAG/HI3LAG-7"))
        assertTrue(f.contains("b/HI3LAG"))
        assertTrue(f.contains("p/HI3"))
        assertFalse(f.contains("t/m"))
    }

    @Test
    fun build_withoutPosition_stillRequestsRegionalAndOwnMessages() {
        val f = AprsIsFilter.build("HI3LAG-7", 100, null, null)
        assertFalse(f.contains("r/"))
        assertTrue(f.contains("m/100"))
        assertTrue(f.contains("g/HI3LAG/HI3LAG-7"))
        assertTrue(f.contains("p/HI3"))
    }

    @Test
    fun regionalPrefix_usesCallArea() {
        assertEquals("HI3", AprsIsFilter.regionalPrefix("HI3LAG"))
        assertEquals("N0C", AprsIsFilter.regionalPrefix("N0CALL"))
        assertEquals("EA", AprsIsFilter.regionalPrefix("EA1"))
    }
}
