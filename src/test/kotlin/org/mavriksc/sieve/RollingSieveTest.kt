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
    fun wheelAdvanceUsesFixedTranslationAndRotationSteps() {
        val wheel = RollingWheel(base = 5, speedUnitsPerFrame = 0.25f)

        wheel.advance(maxDistanceUnits = 100f)
        val firstDistance = wheel.distanceUnits
        val firstRotation = wheel.rotationRadians
        wheel.advance(maxDistanceUnits = 100f)

        assertEquals(5.25f, firstDistance, 0.001f)
        assertEquals(5.5f, wheel.distanceUnits, 0.001f)
        assertEquals(firstRotation * 2f, wheel.rotationRadians, 0.001f)
    }

    @Test
    fun firstWheelDarkensEvensAndSpawnsThreeAfterPassingThree() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 3, speedUnitsPerFrame = 10f)

        sieve.advanceFrame()

        assertNull(sieve.darkenedBy(2))
        assertEquals(2, sieve.darkenedBy(4))
        assertEquals(2, sieve.darkenedBy(10))
        assertNull(sieve.darkenedBy(3))
        assertTrue(3 in sieve.spawnedBases())
    }

    @Test
    fun firstWheelSpawnsThreeAfterFullyClearingThreeCell() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 3, speedUnitsPerFrame = 1f)

        assertFalse(3 in sieve.spawnedBases())

        sieve.advanceFrame()
        assertFalse(3 in sieve.spawnedBases())

        sieve.advanceFrame()
        assertTrue(3 in sieve.spawnedBases())
    }

    @Test
    fun wheelsStartFromTheirBaseCell() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 3, speedUnitsPerFrame = 1f)

        assertEquals(2f, sieve.activeWheels().single().distanceUnits)
        assertNull(sieve.darkenedBy(2))

        repeat(2) { sieve.advanceFrame() }
        val spawned = sieve.activeWheels().first { it.base == 3 }
        assertEquals(3f, spawned.distanceUnits)
        assertNull(sieve.darkenedBy(3))
    }

    @Test
    fun threeDoesNotSpawnBeforeTheLastActiveCircleFullyClearsIt() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 3, speedUnitsPerFrame = 0.5f)

        repeat(3) { sieve.advanceFrame() }
        assertFalse(3 in sieve.spawnedBases())

        sieve.advanceFrame()
        assertTrue(3 in sieve.spawnedBases())
    }

    @Test
    fun spawnedThreeDarkensThirdsWithoutOverwritingEarlierDarkSegments() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 3, speedUnitsPerFrame = 10f)

        sieve.advanceFrame()
        sieve.advanceFrame()

        assertNull(sieve.darkenedBy(3))
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

        repeat(5) { sieve.advanceFrame() }

        assertTrue(5 in sieve.spawnedBases())
        assertFalse(7 in sieve.spawnedBases())
    }

    @Test
    fun doesNotSpawnWheelsLargerThanSquareRootOfLargestCell() {
        val sieve = RollingSieve(unitsPerRow = 10, rowCount = 2, speedUnitsPerFrame = 10f)

        repeat(4) { sieve.advanceFrame() }

        assertTrue(3 in sieve.spawnedBases())
        assertFalse(5 in sieve.spawnedBases())
        assertEquals(5, sieve.lowestLiveSegment())
        assertNull(sieve.lowestSpawnableSegment())
    }
}
