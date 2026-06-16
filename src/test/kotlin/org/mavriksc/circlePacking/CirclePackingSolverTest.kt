package org.mavriksc.circlePacking

import kotlin.test.*

class CirclePackingSolverTest {
    private fun solver(
        boardWidth: Float = 200f,
        boardHeight: Float = 200f,
        targetRadius: Float = 10f,
        maxCircles: Int = 10,
        solveStep: Float = 1f,
        forceStep: Float = 1f,
        flashFrames: Int = 0,
        stallLimit: Int = 2,
        mode: SolverMode = SolverMode.HEURISTIC,
    ) = CirclePackingSolver(
        boardWidth = boardWidth,
        boardHeight = boardHeight,
        targetRadius = targetRadius,
        maxCircles = maxCircles,
        solveStep = solveStep,
        forceStep = forceStep,
        flashFrames = flashFrames,
        stallLimit = stallLimit,
        branchDepthLimit = 2,
        branchWidth = 2,
    ).also { it.solverMode = mode }

    @Test
    fun overlapDetectionFindsEqualRadiusIntersection() {
        val first = CircleCandidate(1, Vec2(50f, 50f), 10f, mutableSetOf(0))
        val second = CircleCandidate(2, Vec2(65f, 50f), 10f, mutableSetOf(1))

        assertTrue(circlesOverlap(first, second))
    }

    @Test
    fun solvedStateAllowsCircleCentersOutsideBoard() {
        val localSolver = solver(maxCircles = 1)
        val points = listOf(SamplePoint(Vec2(0f, 0f)))

        localSolver.candidates.clear()
        localSolver.candidates += CircleCandidate(
            id = 1,
            center = Vec2(-10f, 0f),
            radius = 10f,
            coveredPointIndices = mutableSetOf(0),
        )

        assertTrue(localSolver.isSolved(points))
    }

    @Test
    fun requiredRadiusMatchesCoveredPointDistance() {
        val localSolver = solver()
        val points = listOf(
            SamplePoint(Vec2(20f, 20f)),
            SamplePoint(Vec2(26f, 20f)),
        )
        val candidate = CircleCandidate(1, Vec2(20f, 20f), 0f, mutableSetOf(0, 1))

        assertEquals(6f, localSolver.requiredRadius(candidate, points), 0.001f)
    }

    @Test
    fun pointSpacingResolutionKeepsPointsAtLeastFivePixelsApart() {
        val points = listOf(
            SamplePoint(Vec2(50f, 50f)),
            SamplePoint(Vec2(52f, 50f)),
        )

        resolvePointSpacing(points, minSpacing = 5f, boardWidth = 200f, boardHeight = 200f)

        assertTrue(distance(points[0].position, points[1].position) >= 5f - 0.01f)
    }

    @Test
    fun mergePreviewSeedsMidpointAndCombinesCoveredSets() {
        val localSolver = solver()
        val points = listOf(
            SamplePoint(Vec2(10f, 10f)),
            SamplePoint(Vec2(18f, 10f)),
        )
        val first = CircleCandidate(1, Vec2(10f, 10f), 0f, mutableSetOf(0))
        val second = CircleCandidate(2, Vec2(18f, 10f), 0f, mutableSetOf(1))

        val merged = localSolver.mergeCandidatesPreview(first, second, points, id = 99)

        assertNotNull(merged)
        assertEquals(setOf(0, 1), merged.coveredPointIndices)
        assertEquals(14f, merged.center.x, 0.75f)
        assertEquals(10f, merged.center.y, 0.75f)
        assertEquals(4f, merged.radius, 0.001f)
    }

    @Test
    fun mergePreviewRejectsMidpointMergeThatCannotFitTargetRadius() {
        val localSolver = solver(targetRadius = 10f)
        val points = listOf(
            SamplePoint(Vec2(0f, 0f)),
            SamplePoint(Vec2(50f, 0f)),
            SamplePoint(Vec2(0f, 50f)),
        )
        val first = CircleCandidate(1, Vec2(0f, 0f), 10f, mutableSetOf(0, 1))
        val second = CircleCandidate(2, Vec2(0f, 50f), 10f, mutableSetOf(2))

        val merged = localSolver.mergeCandidatesPreview(first, second, points, id = 99)

        assertEquals(null, merged)
    }

    @Test
    fun widelySpacedPointsFinishWithOneCirclePerPoint() {
        val localSolver = solver(maxCircles = 4)
        val points = listOf(
            SamplePoint(Vec2(20f, 20f)),
            SamplePoint(Vec2(60f, 20f)),
            SamplePoint(Vec2(100f, 20f)),
            SamplePoint(Vec2(140f, 20f)),
        )

        localSolver.reset(points, SolvePhase.GROWING)
        repeat(16) { localSolver.step(points) }

        assertTrue(localSolver.isSolved(points))
        assertEquals(SolvePhase.COMPLETE, localSolver.phase)
        assertEquals(4, localSolver.activeCandidateCount())
    }

    @Test
    fun solveFullyCompletesToFinalCircleSizes() {
        val localSolver = solver(maxCircles = 4, solveStep = 2f)
        val points = listOf(
            SamplePoint(Vec2(20f, 20f)),
            SamplePoint(Vec2(60f, 20f)),
            SamplePoint(Vec2(100f, 20f)),
            SamplePoint(Vec2(140f, 20f)),
        )

        localSolver.solveFully(points, maxSteps = 128)

        assertEquals(SolvePhase.COMPLETE, localSolver.phase)
        assertTrue(localSolver.isSolved(points))
        assertTrue(localSolver.candidates.all { it.radius == 10f })
    }

    @Test
    fun solveFullyHonorsSeededMergeGroups() {
        val localSolver = solver(maxCircles = 4, solveStep = 2f)
        val points = listOf(
            SamplePoint(Vec2(20f, 20f)),
            SamplePoint(Vec2(28f, 20f)),
            SamplePoint(Vec2(100f, 20f)),
        )

        localSolver.solveFully(points, maxSteps = 128, mergeSeeds = listOf(setOf(0, 1)))

        assertTrue(localSolver.candidates.any { it.coveredPointIndices == setOf(0, 1) })
    }

    @Test
    fun radiusNeverExceedsTargetRadius() {
        val localSolver = solver(maxCircles = 1)
        val points = listOf(
            SamplePoint(Vec2(0f, 0f)),
            SamplePoint(Vec2(50f, 0f)),
        )

        localSolver.reset(points, SolvePhase.GROWING)

        repeat(30) {
            try {
                localSolver.step(points)
            } catch (_: IllegalStateException) {
                return@repeat
            }
            assertTrue(localSolver.candidates.all { it.radius <= 10.0001f })
        }
    }

    @Test
    fun overlappingSinglesShiftImmediatelyOnFirstTouchStep() {
        val localSolver = solver(maxCircles = 2, solveStep = 8f, forceStep = 1.5f, stallLimit = 10)
        val points = listOf(
            SamplePoint(Vec2(50f, 50f)),
            SamplePoint(Vec2(62f, 50f)),
        )

        localSolver.reset(points, SolvePhase.GROWING)
        localSolver.step(points)

        val first = localSolver.candidates.first { it.coveredPointIndices == setOf(0) }
        val second = localSolver.candidates.first { it.coveredPointIndices == setOf(1) }
        assertTrue(first.center.x < 50f)
        assertTrue(second.center.x > 62f)
        assertEquals(2, localSolver.activeCandidateCount())
    }

    @Test
    fun pairwiseOverlapIsSplitRoughlyHalfHalf() {
        val localSolver = solver(maxCircles = 2, solveStep = 8f, forceStep = 1.5f, stallLimit = 10)
        val points = listOf(
            SamplePoint(Vec2(50f, 50f)),
            SamplePoint(Vec2(62f, 50f)),
        )

        localSolver.reset(points, SolvePhase.GROWING)
        localSolver.step(points)

        val first = localSolver.candidates.first { it.coveredPointIndices == setOf(0) }
        val second = localSolver.candidates.first { it.coveredPointIndices == setOf(1) }
        val leftShift = 50f - first.center.x
        val rightShift = second.center.x - 62f
        assertEquals(leftShift, rightShift, 0.05f)
    }

    @Test
    fun circleWithMostTouchesResolvesBeforeOuterCircles() {
        val localSolver = solver(maxCircles = 3, solveStep = 8f, forceStep = 1.5f, stallLimit = 10)
        val points = listOf(
            SamplePoint(Vec2(50f, 50f)),
            SamplePoint(Vec2(62f, 50f)),
            SamplePoint(Vec2(74f, 50f)),
        )

        localSolver.reset(points, SolvePhase.GROWING)
        localSolver.step(points)

        val left = localSolver.candidates.first { it.coveredPointIndices == setOf(0) }
        val middle = localSolver.candidates.first { it.coveredPointIndices == setOf(1) }
        val right = localSolver.candidates.first { it.coveredPointIndices == setOf(2) }
        assertTrue(left.center.x < 50f)
        assertTrue(right.center.x > 74f)
        assertTrue(middle.center.x in 61f..63f)
    }

    @Test
    fun isolatedCircleDriftsBackTowardAnchor() {
        val localSolver = solver(targetRadius = 12f, maxCircles = 1, solveStep = 2f)
        val points = listOf(SamplePoint(Vec2(50f, 50f)))

        localSolver.reset(points, SolvePhase.GROWING)
        localSolver.candidates[0].center = Vec2(40f, 50f)
        localSolver.candidates[0].radius = 12f

        localSolver.step(points)

        assertTrue(localSolver.candidates[0].center.x > 40f)
        assertTrue(localSolver.candidates[0].center.x <= 50f)
    }

    @Test
    fun resetWithMergedSeedUsesClosestPairMidpoint() {
        val localSolver = solver()
        val points = listOf(
            SamplePoint(Vec2(10f, 10f)),
            SamplePoint(Vec2(50f, 10f)),
            SamplePoint(Vec2(12f, 12f)),
        )

        localSolver.reset(points, SolvePhase.GROWING, mergeSeeds = listOf(setOf(0, 1, 2)))

        val merged = localSolver.candidates.single()
        assertEquals(setOf(0, 1, 2), merged.coveredPointIndices)
        assertEquals(11f, merged.center.x, 0.75f)
        assertEquals(11f, merged.center.y, 0.75f)
    }

    @Test
    fun mergesClosestPairWhenCircleCountMustReduce() {
        val localSolver = solver(maxCircles = 2, flashFrames = 0)
        val points = listOf(
            SamplePoint(Vec2(0f, 0f)),
            SamplePoint(Vec2(18f, 0f)),
            SamplePoint(Vec2(100f, 0f)),
        )

        localSolver.reset(points, SolvePhase.GROWING)
        repeat(14) { localSolver.step(points) }

        assertEquals(2, localSolver.activeCandidateCount())
        assertTrue(localSolver.candidates.any { it.coveredPointIndices == mutableSetOf(0, 1) || it.coveredPointIndices == setOf(0, 1) })
    }

    @Test
    fun poppingStateFlashesBeforeMergeReplacement() {
        val localSolver = solver(maxCircles = 2, flashFrames = 2)
        val points = listOf(
            SamplePoint(Vec2(0f, 0f)),
            SamplePoint(Vec2(18f, 0f)),
            SamplePoint(Vec2(100f, 0f)),
        )

        localSolver.reset(points, SolvePhase.GROWING)
        repeat(10) { localSolver.step(points) }

        assertEquals(SolvePhase.POPPING, localSolver.phase)
        val beforeIds = localSolver.candidates.map { it.id }.toSet()

        localSolver.step(points)
        assertEquals(SolvePhase.POPPING, localSolver.phase)
        assertEquals(beforeIds, localSolver.candidates.map { it.id }.toSet())

        localSolver.step(points)
        assertFalse(localSolver.candidates.map { it.id }.toSet() == beforeIds)
        assertEquals(2, localSolver.activeCandidateCount())
    }

    @Test
    fun pointsNearWallDoNotFailBecauseCirclesCanLeaveBoard() {
        val localSolver = solver(boardWidth = 600f, boardHeight = 600f, targetRadius = 50f, maxCircles = 2, solveStep = 2f)
        val points = listOf(
            SamplePoint(Vec2(590f, 262f)),
            SamplePoint(Vec2(595f, 322f)),
        )

        localSolver.reset(points, SolvePhase.GROWING)

        repeat(80) {
            localSolver.step(points)
            assertFalse(localSolver.phase == SolvePhase.FAILED)
        }
        assertTrue(localSolver.phase == SolvePhase.COMPLETE || localSolver.phase == SolvePhase.GROWING || localSolver.phase == SolvePhase.RESOLVING)
    }

    @Test
    fun impossibleLayoutThrowsAfterAllMergesAreExhausted() {
        val localSolver = solver(maxCircles = 1)
        val points = listOf(
            SamplePoint(Vec2(0f, 0f)),
            SamplePoint(Vec2(50f, 0f)),
        )

        localSolver.reset(points, SolvePhase.GROWING)

        var failed = false
        repeat(40) {
            try {
                localSolver.step(points)
            } catch (_: IllegalStateException) {
                failed = true
                return@repeat
            }
        }

        assertTrue(failed)
    }

    @Test
    fun candidateCoverSolveFullyFindsNonOverlappingSolution() {
        val localSolver = solver(targetRadius = 10f, maxCircles = 3, mode = SolverMode.CANDIDATE_COVER)
        val points = listOf(
            SamplePoint(Vec2(15f, 15f)),
            SamplePoint(Vec2(20f, 18f)),
            SamplePoint(Vec2(55f, 15f)),
            SamplePoint(Vec2(60f, 18f)),
        )

        localSolver.solveFully(points, maxSteps = 32)

        assertEquals(SolvePhase.COMPLETE, localSolver.phase)
        assertEquals(2, localSolver.activeCandidateCount())
        assertTrue(localSolver.isSolved(points))
    }

    @Test
    fun candidateCoverStepAnimatesTowardMergedGroups() {
        val localSolver = solver(targetRadius = 10f, maxCircles = 3, solveStep = 2f, forceStep = 2f, mode = SolverMode.CANDIDATE_COVER)
        val points = listOf(
            SamplePoint(Vec2(15f, 15f)),
            SamplePoint(Vec2(20f, 18f)),
            SamplePoint(Vec2(55f, 15f)),
            SamplePoint(Vec2(60f, 18f)),
        )

        localSolver.reset(points, SolvePhase.GROWING)
        localSolver.step(points)

        assertEquals(2, localSolver.activeCandidateCount())
        assertTrue(localSolver.candidates.any { it.coveredPointIndices == setOf(0, 1) })
        assertTrue(localSolver.candidates.any { it.coveredPointIndices == setOf(2, 3) })
        assertTrue(localSolver.candidates.all { it.radius in 0f..10f })
        assertTrue(localSolver.phase == SolvePhase.GROWING || localSolver.phase == SolvePhase.COMPLETE)
    }

    @Test
    fun candidateCoverHandlesSparseSingletonPlacementLayout() {
        val localSolver = solver(
            boardWidth = 300f,
            boardHeight = 300f,
            targetRadius = 30f,
            maxCircles = 10,
            solveStep = 1.5f,
            forceStep = 1.5f,
            mode = SolverMode.CANDIDATE_COVER,
        )
        val points = listOf(
            SamplePoint(Vec2(204.033f, 199.06206f)),
            SamplePoint(Vec2(295.73245f, 55.85531f)),
            SamplePoint(Vec2(259.0889f, 281.41852f)),
            SamplePoint(Vec2(26.30361f, 115.67028f)),
            SamplePoint(Vec2(134.0057f, 293.7883f)),
            SamplePoint(Vec2(33.13408f, 188.11493f)),
            SamplePoint(Vec2(156.24118f, 228.4107f)),
            SamplePoint(Vec2(218.07637f, 106.66582f)),
            SamplePoint(Vec2(182.49666f, 248.05994f)),
            SamplePoint(Vec2(208.97998f, 198.33582f)),
        )

        localSolver.solveFully(points, maxSteps = 128)

        assertEquals(SolvePhase.COMPLETE, localSolver.phase)
        assertTrue(localSolver.isSolved(points))
        assertTrue(localSolver.activeCandidateCount() <= 8)
        assertTrue(localSolver.candidates.any { it.coveredPointIndices.size > 1 })
    }
}
