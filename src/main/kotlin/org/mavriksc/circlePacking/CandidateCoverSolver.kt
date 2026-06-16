package org.mavriksc.circlePacking

import kotlin.math.*

private const val CANDIDATE_EPSILON = 0.0001f
private const val SINGLE_POINT_RING_SAMPLES = 8
private const val MAX_CANDIDATES_PER_COVERAGE = 4

internal class CandidateCoverSolver(
    private val targetRadius: Float,
    private val maxCircles: Int,
) {
    fun solve(points: List<SamplePoint>): List<CircleCandidate>? {
        if (points.isEmpty()) return emptyList()
        if (points.size >= Int.SIZE_BITS) {
            throw IllegalArgumentException("candidate cover solver supports at most ${Int.SIZE_BITS - 1} points")
        }

        val generated = buildCandidates(points)
        if (generated.isEmpty()) return null

        val fullMask = (1 shl points.size) - 1
        val conflictMatrix = buildConflictMatrix(generated)
        val candidatesByPoint = List(points.size) { pointIndex ->
            generated.indices.filter { generated[it].covers(pointIndex) }
        }

        for (limit in 1..min(maxCircles, points.size)) {
            val selection = search(
                points = points,
                candidates = generated,
                conflictMatrix = conflictMatrix,
                candidatesByPoint = candidatesByPoint,
                fullMask = fullMask,
                limit = limit,
                coveredMask = 0,
                selectedIndices = mutableListOf(),
                blocked = BooleanArray(generated.size),
            )
            if (selection != null) {
                return selection.mapIndexed { index, candidateIndex ->
                    val candidate = generated[candidateIndex]
                    CircleCandidate(
                        id = index,
                        center = candidate.center.copy(),
                        radius = targetRadius,
                        coveredPointIndices = candidate.coveredPointIndices.toMutableSet(),
                        merged = candidate.coveredPointIndices.size > 1,
                    )
                }
            }
        }
        return null
    }

    private fun buildCandidates(points: List<SamplePoint>): List<GeneratedDisk> {
        val candidates = mutableListOf<GeneratedDisk>()

        fun addCandidate(center: Vec2) {
            val coverageMask = coverageMask(center, points)
            if (coverageMask == 0) return
            val coveredIndices = maskToPointSet(coverageMask, points.size)
            val candidate = GeneratedDisk(center, coverageMask, coveredIndices)
            if (candidates.none { nearlySameCenter(it.center, candidate.center) }) {
                candidates += candidate
            }
        }

        points.forEach { point ->
            addCandidate(point.position.copy())
            repeat(SINGLE_POINT_RING_SAMPLES) { sampleIndex ->
                val angle = ((PI * 2.0) / SINGLE_POINT_RING_SAMPLES.toDouble()) * sampleIndex.toDouble()
                addCandidate(
                    Vec2(
                        point.position.x + cos(angle).toFloat() * targetRadius,
                        point.position.y + sin(angle).toFloat() * targetRadius,
                    )
                )
            }
        }

        for (firstIndex in 0 until points.lastIndex) {
            for (secondIndex in firstIndex + 1 until points.size) {
                val pairCenters = pairCenters(points[firstIndex].position, points[secondIndex].position, targetRadius)
                pairCenters.forEach(::addCandidate)
            }
        }

        return candidates
            .sortedWith(
                compareByDescending<GeneratedDisk> { Integer.bitCount(it.coverageMask) }
                    .thenBy { it.center.x }
                    .thenBy { it.center.y }
            )
            .let(::pruneEquivalentCoverageCandidates)
    }

    private fun buildConflictMatrix(candidates: List<GeneratedDisk>): Array<BooleanArray> {
        val matrix = Array(candidates.size) { BooleanArray(candidates.size) }
        for (firstIndex in 0 until candidates.lastIndex) {
            for (secondIndex in firstIndex + 1 until candidates.size) {
                val conflicts = distance(candidates[firstIndex].center, candidates[secondIndex].center) < (targetRadius * 2f) - CANDIDATE_EPSILON
                matrix[firstIndex][secondIndex] = conflicts
                matrix[secondIndex][firstIndex] = conflicts
            }
        }
        return matrix
    }

    private fun search(
        points: List<SamplePoint>,
        candidates: List<GeneratedDisk>,
        conflictMatrix: Array<BooleanArray>,
        candidatesByPoint: List<List<Int>>,
        fullMask: Int,
        limit: Int,
        coveredMask: Int,
        selectedIndices: MutableList<Int>,
        blocked: BooleanArray,
    ): List<Int>? {
        if (coveredMask == fullMask) {
            return selectedIndices.toList()
        }
        if (selectedIndices.size >= limit) {
            return null
        }

        val uncoveredMask = fullMask and coveredMask.inv()
        val additionalCapacity = maxAdditionalCoverage(candidates, blocked, uncoveredMask)
        if (additionalCapacity == 0) {
            return null
        }
        val minimumExtraDisks = ceil(uncoveredMask.countOneBits().toFloat() / additionalCapacity.toFloat()).toInt()
        if (selectedIndices.size + minimumExtraDisks > limit) {
            return null
        }

        val pivotPoint = selectPivotPoint(points, candidatesByPoint, candidates, blocked, uncoveredMask) ?: return null
        val options = candidatesByPoint[pivotPoint]
            .asSequence()
            .filterNot { blocked[it] }
            .filter { candidates[it].coverageMask and uncoveredMask != 0 }
            .sortedWith(
                compareByDescending<Int> { (candidates[it].coverageMask and uncoveredMask).countOneBits() }
                    .thenBy { conflictCount(it, blocked, conflictMatrix) }
                    .thenBy { distance(candidates[it].center, points[pivotPoint].position) }
            )
            .toList()

        options.forEach { candidateIndex ->
            selectedIndices += candidateIndex
            val nextBlocked = blocked.copyOf()
            nextBlocked[candidateIndex] = true
            for (otherIndex in candidates.indices) {
                if (conflictMatrix[candidateIndex][otherIndex]) {
                    nextBlocked[otherIndex] = true
                }
            }
            val result = search(
                points = points,
                candidates = candidates,
                conflictMatrix = conflictMatrix,
                candidatesByPoint = candidatesByPoint,
                fullMask = fullMask,
                limit = limit,
                coveredMask = coveredMask or candidates[candidateIndex].coverageMask,
                selectedIndices = selectedIndices,
                blocked = nextBlocked,
            )
            if (result != null) {
                return result
            }
            selectedIndices.removeAt(selectedIndices.lastIndex)
        }
        return null
    }

    private fun selectPivotPoint(
        points: List<SamplePoint>,
        candidatesByPoint: List<List<Int>>,
        candidates: List<GeneratedDisk>,
        blocked: BooleanArray,
        uncoveredMask: Int,
    ): Int? {
        var bestPoint: Int? = null
        var bestOptionCount = Int.MAX_VALUE
        var bestCoverage = -1

        for (pointIndex in points.indices) {
            if (uncoveredMask and (1 shl pointIndex) == 0) continue
            val options = candidatesByPoint[pointIndex].filterNot { blocked[it] }
            if (options.isEmpty()) {
                return pointIndex
            }
            val coverage = options.maxOf { (candidates[it].coverageMask and uncoveredMask).countOneBits() }
            if (options.size < bestOptionCount || (options.size == bestOptionCount && coverage > bestCoverage)) {
                bestPoint = pointIndex
                bestOptionCount = options.size
                bestCoverage = coverage
            }
        }
        return bestPoint
    }

    private fun maxAdditionalCoverage(
        candidates: List<GeneratedDisk>,
        blocked: BooleanArray,
        uncoveredMask: Int,
    ): Int = candidates.indices
        .asSequence()
        .filterNot { blocked[it] }
        .map { (candidates[it].coverageMask and uncoveredMask).countOneBits() }
        .maxOrNull()
        ?: 0

    private fun conflictCount(
        candidateIndex: Int,
        blocked: BooleanArray,
        conflictMatrix: Array<BooleanArray>,
    ): Int = conflictMatrix[candidateIndex].indices.count { index ->
        !blocked[index] && conflictMatrix[candidateIndex][index]
    }

    private fun coverageMask(center: Vec2, points: List<SamplePoint>): Int {
        var mask = 0
        points.forEachIndexed { index, point ->
            if (distance(center, point.position) <= targetRadius + CANDIDATE_EPSILON) {
                mask = mask or (1 shl index)
            }
        }
        return mask
    }

    private fun pairCenters(first: Vec2, second: Vec2, radius: Float): List<Vec2> {
        val delta = second - first
        val distanceBetween = delta.magnitude()
        if (distanceBetween <= CANDIDATE_EPSILON || distanceBetween > (radius * 2f) + CANDIDATE_EPSILON) {
            return emptyList()
        }

        val midpoint = Vec2((first.x + second.x) * 0.5f, (first.y + second.y) * 0.5f)
        val halfDistance = distanceBetween * 0.5f
        val offsetMagnitude = sqrt(max(0f, (radius * radius) - (halfDistance * halfDistance)))
        val normal = Vec2(-delta.y / distanceBetween, delta.x / distanceBetween)
        if (offsetMagnitude <= CANDIDATE_EPSILON) {
            return listOf(midpoint)
        }
        return listOf(
            midpoint + (normal * offsetMagnitude),
            midpoint - (normal * offsetMagnitude),
        )
    }

    private fun pruneEquivalentCoverageCandidates(candidates: List<GeneratedDisk>): List<GeneratedDisk> {
        val keptByCoverage = linkedMapOf<Int, MutableList<GeneratedDisk>>()
        candidates.forEach { candidate ->
            val family = keptByCoverage.getOrPut(candidate.coverageMask) { mutableListOf() }
            val minSeparation = targetRadius * 0.35f
            val tooCloseToExisting = family.any { distance(it.center, candidate.center) < minSeparation }
            if (tooCloseToExisting) {
                return@forEach
            }
            if (family.size < MAX_CANDIDATES_PER_COVERAGE) {
                family += candidate
            }
        }
        return keptByCoverage.values.flatten()
    }
}

private data class GeneratedDisk(
    val center: Vec2,
    val coverageMask: Int,
    val coveredPointIndices: Set<Int>,
) {
    fun covers(pointIndex: Int): Boolean = coverageMask and (1 shl pointIndex) != 0
}

private fun maskToPointSet(mask: Int, pointCount: Int): Set<Int> = buildSet {
    for (index in 0 until pointCount) {
        if (mask and (1 shl index) != 0) {
            add(index)
        }
    }
}

private fun nearlySameCenter(first: Vec2, second: Vec2): Boolean =
    distance(first, second) <= 0.01f
