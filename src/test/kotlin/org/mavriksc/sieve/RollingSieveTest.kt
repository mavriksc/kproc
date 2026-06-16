package org.mavriksc.sieve

import kotlin.test.*

class RollingSieveTest {
    @Test
    fun piePatchArcLengthIsOneUnitSegment() {
        val wheel = RollingWheel(base = 2)
        val radius = wheel.radiusPixels(unitWidth = 10f)
        val arcRadians = wheel.unitArcRadians(unitWidth = 10f)

        assertEquals(10f, radius * arcRadians, 0.001f)
    }

    @Test
    fun firstWheelDarkensEvenSegmentsAndSpawnsThreeAfterPassingThree() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 3, speedUnitsPerFrame = 10f)

        sieve.advanceFrame()

        assertEquals(2, sieve.darkenedBy(2))
        assertEquals(2, sieve.darkenedBy(4))
        assertEquals(2, sieve.darkenedBy(10))
        assertNull(sieve.darkenedBy(3))
        assertTrue(3 in sieve.spawnedBases())
    }

    @Test
    fun firstWheelSpawnsThreeWithoutWaitingForEndOfRow() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 3, speedUnitsPerFrame = 1f)

        repeat(2) { sieve.advanceFrame() }
        assertFalse(3 in sieve.spawnedBases())

        sieve.advanceFrame()
        assertTrue(3 in sieve.spawnedBases())
    }

    @Test
    fun spawnedThreeDarkensThirdsWithoutOverwritingEarlierDarkSegments() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 3, speedUnitsPerFrame = 10f)

        sieve.advanceFrame()
        sieve.advanceFrame()

        assertEquals(3, sieve.darkenedBy(3))
        assertEquals(2, sieve.darkenedBy(6))
        assertEquals(3, sieve.darkenedBy(9))
    }

    @Test
    fun nextSpawnUsesLowestSegmentNotTurnedOffOrAlreadySpawned() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 3, speedUnitsPerFrame = 10f)

        repeat(2) { sieve.advanceFrame() }

        assertTrue(5 in sieve.spawnedBases())
        assertFalse(7 in sieve.spawnedBases())
    }

    @Test
    fun fiveAndSevenDoNotSpawnInTheSameFrame() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 4, speedUnitsPerFrame = 1f)

        repeat(8) { sieve.advanceFrame() }

        assertTrue(5 in sieve.spawnedBases())
        assertFalse(7 in sieve.spawnedBases())
    }
}
