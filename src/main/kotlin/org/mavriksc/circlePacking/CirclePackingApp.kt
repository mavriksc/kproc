package org.mavriksc.circlePacking

import processing.core.PApplet
import kotlin.math.*
import kotlin.random.Random

fun main(): Unit = PApplet.main("org.mavriksc.circlePacking.CirclePackingApp")

private const val BOARD_WIDTH = 300
private const val BOARD_HEIGHT = 300
private const val HUD_HEIGHT = 88
private const val MIN_WINDOW_WIDTH = 700
private const val TARGET_RADIUS = 30f
private const val POINT_COUNT = 10
private const val MAX_CIRCLES = 10
private const val SOLVE_STEP = 0.75f
private const val FORCE_STEP = 0.9f
private const val RANDOM_MOTION_DELTA = 0.35f
private const val RANDOM_MOTION_SPEED = 1.2f
private const val PING_PONG_SPEED = 1.6f
private const val STALL_LIMIT = 18
private const val FLASH_FRAMES = 12
private const val GRAB_RADIUS = 10f
private const val MIN_POINT_SPACING = 5f
private const val BRANCH_DEPTH_LIMIT = 4
private const val BRANCH_WIDTH = 4
private const val RELAX_ITERATIONS = 40
private const val FULL_SOLVE_STEP_LIMIT = 4096
private const val EPSILON = 0.0001f
private val EFFECTIVE_MIN_POINT_SPACING = max(MIN_POINT_SPACING, SOLVE_STEP + EPSILON)
private val WINDOW_WIDTH = max(BOARD_WIDTH, MIN_WINDOW_WIDTH)

enum class AppMode { MANUAL, MOTION }
enum class MotionMode { RANDOM, PING_PONG }
enum class SolverMode { HEURISTIC, CANDIDATE_COVER }
enum class SolvePhase { IDLE, GROWING, RESOLVING, POPPING, COMPLETE, FAILED }
enum class CandidateStatus { ACTIVE, POPPING, RETIRED }

data class Vec2(var x: Float, var y: Float) {
    fun copy() = Vec2(x, y)
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scale: Float) = Vec2(x * scale, y * scale)
    fun add(other: Vec2) {
        x += other.x
        y += other.y
    }

    fun subtract(other: Vec2) {
        x -= other.x
        y -= other.y
    }

    fun magnitude(): Float = sqrt(x * x + y * y)

    fun normalized(): Vec2 {
        val magnitude = magnitude()
        return if (magnitude <= EPSILON) Vec2(0f, 0f) else Vec2(x / magnitude, y / magnitude)
    }
}

data class SamplePoint(
    var position: Vec2,
    var velocity: Vec2 = Vec2(0f, 0f),
    var isSelected: Boolean = false,
)

data class CircleCandidate(
    val id: Int,
    var center: Vec2,
    var radius: Float,
    val coveredPointIndices: MutableSet<Int>,
    var status: CandidateStatus = CandidateStatus.ACTIVE,
    var merged: Boolean = false,
    var flashFramesRemaining: Int = 0,
    var stallSteps: Int = 0,
    var lastMoveMagnitude: Float = 0f,
) {
    fun deepCopy(): CircleCandidate = CircleCandidate(
        id = id,
        center = center.copy(),
        radius = radius,
        coveredPointIndices = coveredPointIndices.toMutableSet(),
        status = status,
        merged = merged,
        flashFramesRemaining = flashFramesRemaining,
        stallSteps = stallSteps,
        lastMoveMagnitude = lastMoveMagnitude,
    )
}

data class RelaxedCandidate(val center: Vec2, val requiredRadius: Float)

data class CandidatePair(val firstId: Int, val secondId: Int, val distance: Float)

private data class PendingMerge(
    val poppedIds: Set<Int>,
    val nextCandidates: List<CircleCandidate>,
)

private data class MergePlan(
    val poppedIds: Set<Int>,
    val firstStepCandidates: List<CircleCandidate>,
)

class CirclePackingApp : PApplet() {
    private val points = mutableListOf<SamplePoint>()
    private val solver = CirclePackingSolver(
        boardWidth = BOARD_WIDTH.toFloat(),
        boardHeight = BOARD_HEIGHT.toFloat(),
        targetRadius = TARGET_RADIUS,
        maxCircles = MAX_CIRCLES,
        solveStep = SOLVE_STEP,
        forceStep = FORCE_STEP,
        flashFrames = FLASH_FRAMES,
        stallLimit = STALL_LIMIT,
        branchDepthLimit = BRANCH_DEPTH_LIMIT,
        branchWidth = BRANCH_WIDTH,
    )
    private var appMode = AppMode.MANUAL
    private var motionMode = MotionMode.RANDOM
    private var motionPaused = false
    private var draggedPointIndex: Int? = null

    override fun settings() {
        size(WINDOW_WIDTH, BOARD_HEIGHT + HUD_HEIGHT)
    }

    override fun setup() {
        frameRate(60f)
        strokeJoin(ROUND)
        regeneratePoints()
    }

    override fun draw() {
        background(18)
        drawBoard()

        if (appMode == AppMode.MOTION && !motionPaused) {
            try {
                updatePointsForMotion()
                solver.solveMotionFrame(points, FULL_SOLVE_STEP_LIMIT, motionMergeSeeds())
            } catch (exception: IllegalStateException) {
                handleSolverFailure(exception, fromMotionMode = true)
            }
        } else if (solver.isAnimating()) {
            try {
                solver.step(points)
            } catch (exception: IllegalStateException) {
                handleSolverFailure(exception, fromMotionMode = false)
            }
        }

        renderCandidates()
        renderPoints()
        renderHud()
    }

    override fun mousePressed() {
        if (appMode != AppMode.MANUAL || mouseY > BOARD_HEIGHT) return
        val mouse = Vec2(mouseX.toFloat(), mouseY.toFloat())
        val nearest = points.indices
            .map { it to distance(points[it].position, mouse) }
            .filter { it.second <= GRAB_RADIUS }
            .minByOrNull { it.second }
            ?.first
            ?: return
        draggedPointIndex = nearest
        points[nearest].isSelected = true
    }

    override fun mouseDragged() {
        val index = draggedPointIndex ?: return
        val point = points[index]
        point.position.x = mouseX.toFloat().coerceIn(0f, BOARD_WIDTH.toFloat())
        point.position.y = mouseY.toFloat().coerceIn(0f, BOARD_HEIGHT.toFloat())
        resolvePointSpacing(points, EFFECTIVE_MIN_POINT_SPACING, BOARD_WIDTH.toFloat(), BOARD_HEIGHT.toFloat(), lockedIndices = setOf(index))
    }

    override fun mouseReleased() {
        val index = draggedPointIndex ?: return
        points[index].isSelected = false
        draggedPointIndex = null
        solver.reset(points, SolvePhase.IDLE)
    }

    override fun keyPressed() {
        when (key) {
            ' ' -> solver.reset(points, SolvePhase.GROWING)
            'r', 'R' -> regeneratePoints()
            'm', 'M' -> toggleAppMode()
            's', 'S' -> toggleSolverMode()
            'v', 'V' -> motionMode = if (motionMode == MotionMode.RANDOM) MotionMode.PING_PONG else MotionMode.RANDOM
            'p', 'P' -> motionPaused = !motionPaused
        }
    }

    private fun regeneratePoints() {
        points.clear()
        repeat(POINT_COUNT) {
            val position = Vec2(
                Random.nextFloat() * BOARD_WIDTH,
                Random.nextFloat() * BOARD_HEIGHT,
            )
            points += SamplePoint(position = position, velocity = initialVelocity(MotionMode.PING_PONG))
        }
        resolvePointSpacing(points, EFFECTIVE_MIN_POINT_SPACING, BOARD_WIDTH.toFloat(), BOARD_HEIGHT.toFloat())
        solver.reset(points, if (appMode == AppMode.MOTION) SolvePhase.GROWING else SolvePhase.IDLE)
    }

    private fun toggleAppMode() {
        appMode = if (appMode == AppMode.MANUAL) AppMode.MOTION else AppMode.MANUAL
        motionPaused = false
        solver.reset(points, if (appMode == AppMode.MOTION) SolvePhase.GROWING else SolvePhase.IDLE)
    }

    private fun toggleSolverMode() {
        solver.solverMode = if (solver.solverMode == SolverMode.HEURISTIC) {
            SolverMode.CANDIDATE_COVER
        } else {
            SolverMode.HEURISTIC
        }
        solver.reset(points, if (appMode == AppMode.MOTION) SolvePhase.GROWING else SolvePhase.IDLE)
    }

    private fun updatePointsForMotion() {
        points.forEach { point ->
            if (point.isSelected) return@forEach
            when (motionMode) {
                MotionMode.RANDOM -> {
                    point.velocity.add(
                        Vec2(
                            Random.nextFloat() * 2f * RANDOM_MOTION_DELTA - RANDOM_MOTION_DELTA,
                            Random.nextFloat() * 2f * RANDOM_MOTION_DELTA - RANDOM_MOTION_DELTA,
                        )
                    )
                    point.velocity = point.velocity.normalized() * RANDOM_MOTION_SPEED
                    point.position.add(point.velocity)
                    bounceIntoBoard(point)
                }

                MotionMode.PING_PONG -> {
                    if (point.velocity.magnitude() <= EPSILON) {
                        point.velocity = initialVelocity(MotionMode.PING_PONG)
                    }
                    point.position.add(point.velocity)
                    bounceIntoBoard(point)
                }
            }
        }
        resolvePointSpacing(points, EFFECTIVE_MIN_POINT_SPACING, BOARD_WIDTH.toFloat(), BOARD_HEIGHT.toFloat())
    }

    private fun handleSolverFailure(exception: IllegalStateException, fromMotionMode: Boolean) {
        logSolverFailure(exception)
        if (fromMotionMode) {
            motionPaused = true
            appMode = AppMode.MANUAL
            solver.reset(points, SolvePhase.GROWING)
        } else {
            solver.reset(points, SolvePhase.IDLE)
        }
    }

    private fun logSolverFailure(exception: IllegalStateException) {
        System.err.println(
            buildString {
                appendLine("circle packing solve failed")
                appendLine("mode=$appMode motion=$motionMode paused=$motionPaused")
                appendLine("points=${points.joinToString { "(${it.position.x},${it.position.y})" }}")
                appendLine("message=${exception.message}")
            }
        )
    }

    private fun motionMergeSeeds(): List<Set<Int>> {
        if (solver.candidates.isEmpty()) return emptyList()
        val predictedPositions = points.mapIndexed { index, point ->
            if (point.isSelected) {
                point.position.copy()
            } else {
                predictNextPosition(point)
            }
        }
        val seeds = mutableListOf<Set<Int>>()
        points.indices.forEach { pointIndex ->
            val predicted = predictedPositions[pointIndex]
            solver.candidates
                .filter { it.status == CandidateStatus.ACTIVE && pointIndex !in it.coveredPointIndices }
                .forEach { candidate ->
                    if (distance(predicted, candidate.center) <= candidate.radius + EPSILON) {
                        seeds += candidate.coveredPointIndices + pointIndex
                    }
                }
        }
        return seeds
    }

    private fun predictNextPosition(point: SamplePoint): Vec2 {
        val predictedVelocity = when (motionMode) {
            MotionMode.RANDOM -> {
                if (point.velocity.magnitude() <= EPSILON) {
                    initialVelocity(MotionMode.RANDOM)
                } else {
                    point.velocity.normalized() * RANDOM_MOTION_SPEED
                }
            }

            MotionMode.PING_PONG -> {
                if (point.velocity.magnitude() <= EPSILON) {
                    initialVelocity(MotionMode.PING_PONG)
                } else {
                    point.velocity.copy()
                }
            }
        }
        val predictedPoint = SamplePoint(point.position.copy(), predictedVelocity)
        predictedPoint.position.add(predictedPoint.velocity)
        bounceIntoBoard(predictedPoint)
        return predictedPoint.position
    }

    private fun bounceIntoBoard(point: SamplePoint) {
        if (point.position.x < 0f || point.position.x > BOARD_WIDTH) {
            point.velocity.x *= -1f
            point.position.x = point.position.x.coerceIn(0f, BOARD_WIDTH.toFloat())
        }
        if (point.position.y < 0f || point.position.y > BOARD_HEIGHT) {
            point.velocity.y *= -1f
            point.position.y = point.position.y.coerceIn(0f, BOARD_HEIGHT.toFloat())
        }
    }

    private fun initialVelocity(mode: MotionMode): Vec2 {
        val angle = Random.nextFloat() * TWO_PI
        val speed = if (mode == MotionMode.PING_PONG) PING_PONG_SPEED else RANDOM_MOTION_SPEED
        return Vec2(cos(angle) * speed, sin(angle) * speed)
    }

    private fun drawBoard() {
        noFill()
        stroke(90)
        strokeWeight(2f)
        rect(0f, 0f, BOARD_WIDTH.toFloat(), BOARD_HEIGHT.toFloat())
    }

    private fun renderCandidates() {
        solver.candidates.forEach { candidate ->
            val color = candidateColor(candidate)
            strokeWeight(if (candidate.merged) 2.5f else 1.5f)
            when (candidate.status) {
                CandidateStatus.POPPING -> {
                    val flashOn = frameCount % 6 < 3
                    stroke(if (flashOn) color(255, 220, 40) else color(255, 80, 80))
                    fill(255f, 64f, 64f, 32f)
                }

                CandidateStatus.ACTIVE -> {
                    stroke(color)
                    fill(red(color), green(color), blue(color), if (candidate.merged) 28f else 18f)
                }

                CandidateStatus.RETIRED -> return@forEach
            }
            ellipse(candidate.center.x, candidate.center.y, candidate.radius * 2f, candidate.radius * 2f)
        }
    }

    private fun renderPoints() {
        points.forEachIndexed { index, point ->
            strokeWeight(6f)
            stroke(if (point.isSelected) color(255, 200, 80) else color(255))
            point(point.position.x, point.position.y)
            fill(255)
            textSize(9f)
            text(index.toString(), point.position.x + 4f, point.position.y - 4f)
        }
    }

    private fun renderHud() {
        fill(235)
        textSize(11f)
        text("mode: $appMode   motion: $motionMode   paused: $motionPaused", 8f, BOARD_HEIGHT + 18f)
        text("solver: ${solver.solverMode}   phase: ${solver.phase}   circles: ${solver.activeCandidateCount()}", 8f, BOARD_HEIGHT + 34f)
        text("defaults: ${BOARD_WIDTH}x${BOARD_HEIGHT}  r=$TARGET_RADIUS  points=$POINT_COUNT", 8f, BOARD_HEIGHT + 50f)
        text("space solve  r respawn  m manual/motion  s solver  v motion type  p pause", 8f, BOARD_HEIGHT + 66f)
        text("heuristic = grow/move/merge   candidate = fixed-radius candidate cover search", 8f, BOARD_HEIGHT + 82f)
    }

    private fun candidateColor(candidate: CircleCandidate): Int {
        colorMode(HSB, 360f, 100f, 100f, 100f)
        val hue = (candidate.id * 47) % 360
        val saturation = if (candidate.merged) 75f else 55f
        val brightness = if (candidate.status == CandidateStatus.POPPING) 100f else 92f
        val color = color(hue.toFloat(), saturation, brightness, 100f)
        colorMode(RGB, 255f)
        return color
    }
}

class CirclePackingSolver(
    private val boardWidth: Float,
    private val boardHeight: Float,
    private val targetRadius: Float,
    private val maxCircles: Int,
    private val solveStep: Float,
    private val forceStep: Float,
    private val flashFrames: Int,
    private val stallLimit: Int,
    private val branchDepthLimit: Int,
    private val branchWidth: Int,
) {
    var solverMode: SolverMode = SolverMode.CANDIDATE_COVER
    var phase: SolvePhase = SolvePhase.IDLE
        private set
    var candidates: MutableList<CircleCandidate> = mutableListOf()
        private set

    private val candidateCoverSolver = CandidateCoverSolver(targetRadius = targetRadius, maxCircles = maxCircles)
    private var nextCandidateId = 0
    private var pendingMerge: PendingMerge? = null
    private var targetSolution: List<CircleCandidate>? = null

    fun reset(
        points: List<SamplePoint>,
        nextPhase: SolvePhase = SolvePhase.IDLE,
        mergeSeeds: List<Set<Int>> = emptyList(),
    ) {
        nextCandidateId = 0
        pendingMerge = null
        targetSolution = null
        val mergedGroups = normalizeMergeGroups(points.size, mergeSeeds)
        val assignedPoints = mergedGroups.flatten().toSet()
        val initialGroups = buildList {
            addAll(mergedGroups)
            points.indices
                .filterNot { it in assignedPoints }
                .forEach { add(setOf(it)) }
        }
        candidates = initialGroups.map { coveredPoints ->
            CircleCandidate(
                id = nextCandidateId++,
                center = seedCenterForGroup(coveredPoints, points),
                radius = 0f,
                coveredPointIndices = coveredPoints.toMutableSet(),
            )
        }.toMutableList()
        phase = nextPhase
    }

    fun activeCandidateCount(): Int = candidates.count { it.status == CandidateStatus.ACTIVE }

    fun isAnimating(): Boolean = phase == SolvePhase.GROWING || phase == SolvePhase.RESOLVING || phase == SolvePhase.POPPING

    fun solveFully(
        points: List<SamplePoint>,
        maxSteps: Int = FULL_SOLVE_STEP_LIMIT,
        mergeSeeds: List<Set<Int>> = emptyList(),
    ) {
        if (solverMode == SolverMode.CANDIDATE_COVER) {
            reset(points, SolvePhase.GROWING, mergeSeeds)
            val solution = candidateCoverSolver.solve(points)
                ?: throw illegalSolveState(buildFailureMessage("candidate cover solver could not find a valid cover", points))
            candidates = solution.map { it.deepCopy() }.toMutableList()
            targetSolution = solution.map { it.deepCopy() }
            phase = SolvePhase.COMPLETE
            return
        }
        reset(points, SolvePhase.GROWING, mergeSeeds)
        repeat(maxSteps) {
            if (phase == SolvePhase.COMPLETE) return
            step(points)
        }
        if (phase != SolvePhase.COMPLETE) {
            throw illegalSolveState(
                buildString {
                    appendLine("full solve did not converge within step limit")
                    appendLine("phase=$phase")
                    appendLine("points=${points.joinToString { "(${it.position.x},${it.position.y})" }}")
                    appendLine(
                        "candidates=${
                            candidates.joinToString {
                                "id=${it.id},center=(${it.center.x},${it.center.y}),r=${it.radius},covered=${it.coveredPointIndices}"
                            }
                        }"
                    )
                }
            )
        }
    }

    fun solveMotionFrame(
        points: List<SamplePoint>,
        maxSteps: Int = FULL_SOLVE_STEP_LIMIT,
        mergeSeeds: List<Set<Int>> = emptyList(),
    ) {
        if (solverMode != SolverMode.CANDIDATE_COVER) {
            solveFully(points, maxSteps, mergeSeeds)
            return
        }
        if (tryReuseCandidateCoverSolution(points)) {
            phase = SolvePhase.COMPLETE
            return
        }
        solveFully(points, maxSteps, mergeSeeds)
    }

    fun ensureSolvingAfterMotion(points: List<SamplePoint>) {
        if (phase == SolvePhase.IDLE) {
            phase = SolvePhase.GROWING
            return
        }
        if (phase == SolvePhase.COMPLETE && !isSolved(points)) {
            phase = SolvePhase.GROWING
        }
    }

    fun isSolved(points: List<SamplePoint>): Boolean {
        val active = activeCandidates()
        if (active.isEmpty() || active.size > maxCircles) return false
        if (active.any { abs(it.radius - targetRadius) > 0.01f }) return false
        if (active.any { requiredRadius(it, points) > targetRadius + 0.01f }) return false
        return overlappingPairs(active).isEmpty()
    }

    fun step(points: List<SamplePoint>) {
        if (solverMode == SolverMode.CANDIDATE_COVER) {
            stepCandidateCover(points)
            return
        }
        when (phase) {
            SolvePhase.IDLE, SolvePhase.COMPLETE -> return
            SolvePhase.FAILED -> throw illegalSolveState("solver already failed")
            SolvePhase.POPPING -> {
                advancePendingMerge()
                return
            }

            SolvePhase.GROWING, SolvePhase.RESOLVING -> Unit
        }

        if (candidates.isEmpty()) {
            throw illegalSolveState("no candidates available")
        }

        val active = activeCandidates()
        val startCenters = active.associate { it.id to it.center.copy() }
        val preferredCenters = active.associate { it.id to preferredCenter(it.coveredPointIndices, points) }
        active.forEach { candidate ->
            candidate.radius = min(targetRadius, candidate.radius + solveStep)
        }

        resolveTouches(active, preferredCenters)
        val touchCounts = touchCounts(active)
        active.forEach { candidate ->
            val anchorStep = if ((touchCounts[candidate.id] ?: 0) > 0) solveStep * 0.15f else solveStep * 0.6f
            val anchorTarget = preferredCenters.getValue(candidate.id)
            val proposal = steerTowards(candidate.center, anchorTarget, anchorStep)
            if (!wouldOverlap(candidate, proposal, active)) {
                candidate.center = proposal
            }
            candidate.center = projectCenterIntoCurrentRadius(candidate, points)
            candidate.lastMoveMagnitude = distance(startCenters.getValue(candidate.id), candidate.center)
        }

        val overlaps = overlappingPairs(activeCandidates())
        val loneCandidate = activeCandidates().singleOrNull()
        if (loneCandidate != null && requiredRadius(loneCandidate, points) > targetRadius + 0.01f) {
            throw illegalSolveState(
                buildString {
                    appendLine("single candidate cannot satisfy target radius")
                    appendLine("phase=$phase")
                    appendLine("points=${points.joinToString { "(${it.position.x},${it.position.y})" }}")
                    appendLine(
                        "candidates=${
                            candidates.joinToString {
                                "id=${it.id},center=(${it.center.x},${it.center.y}),r=${it.radius},covered=${it.coveredPointIndices}"
                            }
                        }"
                    )
                }
            )
        }
        val blocked = selectBlockedCandidate(points, overlaps) ?: selectCountReductionCandidate(points)
        if (blocked != null) {
            scheduleMerge(blocked, points)
            return
        }

        phase = if (overlaps.isNotEmpty()) SolvePhase.RESOLVING else SolvePhase.GROWING
        if (isSolved(points)) {
            phase = SolvePhase.COMPLETE
        }
    }

    private fun stepCandidateCover(points: List<SamplePoint>) {
        when (phase) {
            SolvePhase.IDLE, SolvePhase.COMPLETE -> return
            SolvePhase.FAILED -> throw illegalSolveState("solver already failed")
            SolvePhase.POPPING -> phase = SolvePhase.GROWING
            SolvePhase.GROWING, SolvePhase.RESOLVING -> Unit
        }

        val solution = targetSolution ?: candidateCoverSolver.solve(points)
            ?.map { it.deepCopy() }
            ?: throw illegalSolveState(buildFailureMessage("candidate cover solver could not find a valid cover", points))

        targetSolution = solution.map { it.deepCopy() }
        val currentByCoverage = candidates.associateBy { it.coveredPointIndices.toSet() }
        candidates = solution.map { target ->
            val current = currentByCoverage[target.coveredPointIndices]
            val startCenter = current?.center ?: seedCenterForGroup(target.coveredPointIndices, points)
            val nextCenter = steerTowards(startCenter, target.center, max(forceStep * 2.5f, solveStep * 3f))
            val startRadius = current?.radius ?: 0f
            CircleCandidate(
                id = target.id,
                center = nextCenter,
                radius = min(targetRadius, startRadius + solveStep),
                coveredPointIndices = target.coveredPointIndices.toMutableSet(),
                merged = target.coveredPointIndices.size > 1,
            )
        }.toMutableList()

        val aligned = candidates.zip(solution).all { (current, target) ->
            distance(current.center, target.center) <= 0.05f && abs(current.radius - targetRadius) <= 0.05f
        }
        phase = if (aligned) SolvePhase.COMPLETE else SolvePhase.GROWING
    }

    private fun tryReuseCandidateCoverSolution(points: List<SamplePoint>): Boolean {
        val active = activeCandidates()
        if (active.isEmpty()) return false
        if (active.flatMap { it.coveredPointIndices }.toSet().size != points.size) return false

        val relaxed = active.map { candidate ->
            val fitted = relaxCenter(candidate.center, candidate.coveredPointIndices, points, targetRadius)
            CircleCandidate(
                id = candidate.id,
                center = fitted.center,
                radius = targetRadius,
                coveredPointIndices = candidate.coveredPointIndices.toMutableSet(),
                merged = candidate.merged || candidate.coveredPointIndices.size > 1,
            )
        }

        val validCoverage = relaxed.all { requiredRadius(it, points) <= targetRadius + 0.01f }
        if (!validCoverage) return false
        if (overlappingPairs(relaxed).isNotEmpty()) return false

        candidates = relaxed.toMutableList()
        targetSolution = relaxed.map { it.deepCopy() }
        return true
    }

    internal fun overlappingPairs(candidatesToCheck: List<CircleCandidate> = activeCandidates()): List<Pair<Int, Int>> {
        val overlaps = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until candidatesToCheck.lastIndex) {
            for (j in i + 1 until candidatesToCheck.size) {
                if (circlesOverlap(candidatesToCheck[i], candidatesToCheck[j])) {
                    overlaps += candidatesToCheck[i].id to candidatesToCheck[j].id
                }
            }
        }
        return overlaps
    }

    internal fun requiredRadius(candidate: CircleCandidate, points: List<SamplePoint>): Float =
        maxDistanceToCoveredPoints(candidate.center, candidate.coveredPointIndices, points)

    internal fun mergeCandidatesPreview(
        first: CircleCandidate,
        second: CircleCandidate,
        points: List<SamplePoint>,
        id: Int = nextCandidateId,
    ): CircleCandidate? {
        val mergedPoints = (first.coveredPointIndices + second.coveredPointIndices).toMutableSet()
        val mergedCenter = midpointOfClosestCoveredPoints(first, second, points)
        val requiredRadius = maxDistanceToCoveredPoints(mergedCenter, mergedPoints, points)
        if (requiredRadius > targetRadius + EPSILON) {
            return null
        }
        return CircleCandidate(
            id = id,
            center = mergedCenter,
            radius = requiredRadius.coerceAtMost(targetRadius),
            coveredPointIndices = mergedPoints,
            merged = true,
        )
    }

    private fun activeCandidates(): List<CircleCandidate> = candidates.filter { it.status == CandidateStatus.ACTIVE }

    private fun normalizeMergeGroups(pointCount: Int, mergeSeeds: List<Set<Int>>): List<Set<Int>> {
        if (mergeSeeds.isEmpty()) return emptyList()
        val parent = IntArray(pointCount) { it }

        fun find(value: Int): Int {
            var current = value
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun union(first: Int, second: Int) {
            val rootFirst = find(first)
            val rootSecond = find(second)
            if (rootFirst != rootSecond) {
                parent[rootSecond] = rootFirst
            }
        }

        mergeSeeds
            .filter { it.isNotEmpty() }
            .forEach { seed ->
                val ordered = seed.toList()
                ordered.drop(1).forEach { union(ordered.first(), it) }
            }

        return (0 until pointCount)
            .groupBy { find(it) }
            .values
            .map { it.toSet() }
            .filter { it.size > 1 }
    }

    private fun seedCenterForGroup(
        coveredPoints: Set<Int>,
        points: List<SamplePoint>,
    ): Vec2 {
        if (coveredPoints.size <= 1) {
            return points[coveredPoints.first()].position.copy()
        }
        val orderedPoints = coveredPoints.toList()
        var bestPair = orderedPoints.first() to orderedPoints[1]
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until orderedPoints.lastIndex) {
            for (j in i + 1 until orderedPoints.size) {
                val firstIndex = orderedPoints[i]
                val secondIndex = orderedPoints[j]
                val pairDistance = distance(points[firstIndex].position, points[secondIndex].position)
                if (pairDistance < bestDistance) {
                    bestDistance = pairDistance
                    bestPair = firstIndex to secondIndex
                }
            }
        }
        val firstPoint = points[bestPair.first].position
        val secondPoint = points[bestPair.second].position
        return Vec2((firstPoint.x + secondPoint.x) * 0.5f, (firstPoint.y + secondPoint.y) * 0.5f)
    }

    private fun resolveTouches(
        active: List<CircleCandidate>,
        preferredCenters: Map<Int, Vec2>,
    ) {
        val processedPairs = mutableSetOf<Pair<Int, Int>>()
        val counts = touchCounts(active)
        val resolutionOrder = active
            .sortedWith(
                compareByDescending<CircleCandidate> { counts[it.id] ?: 0 }
                    .thenBy { it.id }
            )

        resolutionOrder.forEach { candidate ->
            val currentlyTouching = active
                .filter { it.id != candidate.id && circlesOverlap(candidate, it) }
                .sortedBy { it.id }
            if (currentlyTouching.isEmpty()) return@forEach

            val selfAdjustment = Vec2(0f, 0f)
            val partnerAdjustments = mutableMapOf<Int, Vec2>()
            currentlyTouching.forEach { partner ->
                val pairKey = orderedPair(candidate.id, partner.id)
                if (processedPairs.add(pairKey)) {
                    val separation = pairSeparationVector(candidate, partner, preferredCenters)
                    selfAdjustment.add(separation * 0.5f)
                    partnerAdjustments.getOrPut(partner.id) { Vec2(0f, 0f) }.subtract(separation * 0.5f)
                }
            }

            candidate.center.add(selfAdjustment)
            partnerAdjustments.forEach { (partnerId, adjustment) ->
                active.firstOrNull { it.id == partnerId }?.center?.add(adjustment)
            }
        }
    }

    private fun projectCenterIntoCurrentRadius(candidate: CircleCandidate, points: List<SamplePoint>): Vec2 {
        if (candidate.coveredPointIndices.isEmpty()) return candidate.center
        var center = candidate.center.copy()
        repeat(RELAX_ITERATIONS) {
            val overflowing = candidate.coveredPointIndices
                .map { points[it].position to distance(center, points[it].position) }
                .filter { it.second > candidate.radius + EPSILON }
            if (overflowing.isEmpty()) return center

            val correction = overflowing.fold(Vec2(0f, 0f)) { acc, (point, distanceToPoint) ->
                if (distanceToPoint <= EPSILON) {
                    acc
                } else {
                    acc.add((point - center).normalized() * (distanceToPoint - candidate.radius))
                    acc
                }
            }
            if (correction.magnitude() <= EPSILON) return center
            center.add(limitMagnitude(correction, forceStep))
        }
        return center
    }

    private fun touchCounts(active: List<CircleCandidate>): Map<Int, Int> {
        val counts = active.associate { it.id to 0 }.toMutableMap()
        overlappingPairs(active).forEach { (first, second) ->
            counts[first] = (counts[first] ?: 0) + 1
            counts[second] = (counts[second] ?: 0) + 1
        }
        return counts
    }

    private fun pairSeparationVector(
        first: CircleCandidate,
        second: CircleCandidate,
        preferredCenters: Map<Int, Vec2>,
    ): Vec2 {
        val delta = first.center - second.center
        val distance = delta.magnitude()
        val overlap = first.radius + second.radius - distance
        if (overlap <= EPSILON) return Vec2(0f, 0f)

        val direction = when {
            distance > EPSILON -> delta.normalized()
            else -> {
                val preferredDelta = preferredCenters.getValue(first.id) - preferredCenters.getValue(second.id)
                if (preferredDelta.magnitude() > EPSILON) {
                    preferredDelta.normalized()
                } else {
                    stablePairAxis(first.id, second.id)
                }
            }
        }
        return direction * overlap
    }

    private fun wouldOverlap(
        candidate: CircleCandidate,
        proposedCenter: Vec2,
        active: List<CircleCandidate>,
    ): Boolean = active.any { other ->
        other.id != candidate.id && distance(proposedCenter, other.center) < candidate.radius + other.radius - EPSILON
    }

    private fun advancePendingMerge() {
        val current = pendingMerge ?: return
        val popping = candidates.filter { it.status == CandidateStatus.POPPING }
        popping.forEach { it.flashFramesRemaining -= 1 }
        if (popping.any { it.flashFramesRemaining > 0 }) {
            phase = SolvePhase.POPPING
            return
        }
        candidates = current.nextCandidates.map { it.deepCopy() }.toMutableList()
        pendingMerge = null
        phase = SolvePhase.GROWING
    }

    private fun selectBlockedCandidate(
        points: List<SamplePoint>,
        overlaps: List<Pair<Int, Int>>,
    ): CircleCandidate? {
        val conflictCounts = mutableMapOf<Int, Int>()
        overlaps.forEach { (first, second) ->
            conflictCounts[first] = (conflictCounts[first] ?: 0) + 1
            conflictCounts[second] = (conflictCounts[second] ?: 0) + 1
        }
        val active = activeCandidates()
        val requiredRadii = active.associate { it.id to requiredRadius(it, points) }
        active.forEach { candidate ->
            val conflictCount = conflictCounts[candidate.id] ?: 0
            val stalled = conflictCount > 0 &&
                candidate.radius >= targetRadius - 0.01f &&
                candidate.lastMoveMagnitude < 0.05f
            candidate.stallSteps = if (stalled) candidate.stallSteps + 1 else 0
        }
        val overflowBlocked = active
            .filter { requiredRadii.getValue(it.id) > targetRadius + 0.01f }
            .maxWithOrNull(
                compareByDescending<CircleCandidate> { conflictCounts[it.id] ?: 0 }
                    .thenByDescending { requiredRadii.getValue(it.id) - targetRadius }
                    .thenByDescending { it.coveredPointIndices.size }
            )
        if (overflowBlocked != null) {
            return overflowBlocked
        }
        return active
            .filter { (conflictCounts[it.id] ?: 0) > 0 && it.stallSteps >= stallLimit }
            .maxWithOrNull(
                compareByDescending<CircleCandidate> { conflictCounts[it.id] ?: 0 }
                    .thenByDescending { it.stallSteps }
                    .thenByDescending { it.coveredPointIndices.size }
            )
    }

    private fun selectCountReductionCandidate(points: List<SamplePoint>): CircleCandidate? {
        val active = activeCandidates()
        if (active.size <= maxCircles || active.any { it.radius < targetRadius - 0.01f }) {
            return null
        }
        val closestPair = active
            .flatMapIndexed { index, candidate ->
                active.drop(index + 1).map { other ->
                    CandidatePair(candidate.id, other.id, closestCoveredPointDistance(candidate, other, points))
                }
            }
            .minByOrNull { it.distance }
            ?: return null
        return active.firstOrNull { it.id == closestPair.firstId }
    }

    private fun scheduleMerge(
        blocked: CircleCandidate,
        points: List<SamplePoint>,
    ) {
        val plan = findMergePlan(blocked, points)
            ?: throw illegalSolveState(
                buildString {
                    appendLine("unable to find a valid merge plan")
                    appendLine("phase=$phase")
                    appendLine("points=${points.joinToString { "(${it.position.x},${it.position.y})" }}")
                    appendLine(
                        "candidates=${
                            candidates.joinToString {
                                "id=${it.id},center=(${it.center.x},${it.center.y}),r=${it.radius},covered=${it.coveredPointIndices}"
                            }
                        }"
                    )
                }
            )

        candidates.forEach { candidate ->
            if (candidate.id in plan.poppedIds) {
                candidate.status = CandidateStatus.POPPING
                candidate.flashFramesRemaining = flashFrames
            }
        }
        pendingMerge = PendingMerge(plan.poppedIds, plan.firstStepCandidates)
        phase = SolvePhase.POPPING
    }

    private fun findMergePlan(
        blocked: CircleCandidate,
        points: List<SamplePoint>,
    ): MergePlan? {
        val partnerChoice = activeCandidates()
            .filter { it.id != blocked.id }
            .mapNotNull { partner ->
                val preview = mergeCandidatesPreview(blocked, partner, points, id = nextCandidateId + 1000)
                    ?: return@mapNotNull null
                Triple(partner, requiredRadius(preview, points), closestCoveredPointDistance(blocked, partner, points))
            }
            .minWithOrNull(
                compareBy<Triple<CircleCandidate, Float, Float>> { it.second }
                    .thenBy { it.third }
                    .thenBy { it.first.coveredPointIndices.size }
            )
            ?: return null
        val committed = buildMergedState(candidates, blocked.id, partnerChoice.first.id, points, allocateNewId = true)
            ?: return null
        return MergePlan(setOf(blocked.id, partnerChoice.first.id), committed)
    }

    private fun buildMergedState(
        sourceCandidates: List<CircleCandidate>,
        firstId: Int,
        secondId: Int,
        points: List<SamplePoint>,
        allocateNewId: Boolean = false,
    ): List<CircleCandidate>? {
        val first = sourceCandidates.firstOrNull { it.id == firstId } ?: return null
        val second = sourceCandidates.firstOrNull { it.id == secondId } ?: return null
        val merged = mergeCandidatesPreview(first, second, points, if (allocateNewId) nextCandidateId else nextCandidateId + 1000)
            ?: return null
        if (allocateNewId) {
            nextCandidateId += 1
        }
        return sourceCandidates
            .filterNot { it.id == firstId || it.id == secondId }
            .map { it.deepCopy().also { copy -> copy.status = CandidateStatus.ACTIVE } } +
            merged
    }

    private fun illegalSolveState(message: String): IllegalStateException {
        phase = SolvePhase.FAILED
        return IllegalStateException(message)
    }

    private fun buildFailureMessage(reason: String, points: List<SamplePoint>): String = buildString {
        appendLine(reason)
        appendLine("phase=$phase")
        appendLine("points=${points.joinToString { "(${it.position.x},${it.position.y})" }}")
        appendLine(
            "candidates=${
                candidates.joinToString {
                    "id=${it.id},center=(${it.center.x},${it.center.y}),r=${it.radius},covered=${it.coveredPointIndices}"
                }
            }"
        )
    }
}

internal fun circlesOverlap(first: CircleCandidate, second: CircleCandidate): Boolean =
    distance(first.center, second.center) < first.radius + second.radius - EPSILON

internal fun maxDistanceToCoveredPoints(
    center: Vec2,
    coveredPointIndices: Set<Int>,
    points: List<SamplePoint>,
): Float = coveredPointIndices.maxOfOrNull { index -> distance(center, points[index].position) } ?: 0f

internal fun relaxCenter(
    seedCenter: Vec2,
    coveredPointIndices: Set<Int>,
    points: List<SamplePoint>,
    targetRadius: Float,
): RelaxedCandidate {
    val coveredPoints = coveredPointIndices.map { points[it].position }
    if (coveredPoints.isEmpty()) return RelaxedCandidate(seedCenter.copy(), 0f)

    var center = seedCenter.copy()
    var bestCenter = center.copy()
    var bestRadius = coveredPoints.maxOf { distance(bestCenter, it) }
    var step = max(targetRadius * 0.35f, 1f)

    repeat(RELAX_ITERATIONS) {
        val distances = coveredPoints.map { it to distance(center, it) }
        val worst = distances.maxOf { it.second }
        if (worst < bestRadius) {
            bestRadius = worst
            bestCenter = center.copy()
        }
        val farthest = distances.filter { abs(it.second - worst) < 0.25f }.map { it.first }
        val gradient = farthest.fold(Vec2(0f, 0f)) { acc, point ->
            acc.add(point - center)
            acc
        }
        if (gradient.magnitude() <= EPSILON) return@repeat
        val proposal = center + gradient.normalized() * step
        val proposalRadius = coveredPoints.maxOf { distance(proposal, it) }
        if (proposalRadius <= bestRadius + 0.001f) {
            center = proposal
            if (proposalRadius < bestRadius) {
                bestCenter = proposal.copy()
                bestRadius = proposalRadius
            }
        } else {
            step *= 0.6f
        }
    }

    val centroid = coveredPoints.fold(Vec2(0f, 0f)) { acc, point ->
        acc.add(point)
        acc
    } * (1f / coveredPoints.size.toFloat())
    val centroidRadius = coveredPoints.maxOf { distance(centroid, it) }
    if (centroidRadius < bestRadius) {
        bestCenter = centroid
        bestRadius = centroidRadius
    }
    return RelaxedCandidate(bestCenter, bestRadius)
}

internal fun steerTowards(from: Vec2, to: Vec2, maxStep: Float): Vec2 {
    val delta = to - from
    val magnitude = delta.magnitude()
    if (magnitude <= maxStep) return to.copy()
    if (magnitude <= EPSILON) return from.copy()
    return from + delta.normalized() * maxStep
}

internal fun limitMagnitude(vector: Vec2, maxMagnitude: Float): Vec2 {
    val magnitude = vector.magnitude()
    if (magnitude <= maxMagnitude) return vector
    if (magnitude <= EPSILON) return Vec2(0f, 0f)
    return vector.normalized() * maxMagnitude
}

internal fun preferredCenter(
    coveredPointIndices: Set<Int>,
    points: List<SamplePoint>,
): Vec2 {
    val coveredPoints = coveredPointIndices.map { points[it].position }
    if (coveredPoints.isEmpty()) return Vec2(0f, 0f)
    return coveredPoints.fold(Vec2(0f, 0f)) { acc, point ->
        acc.add(point)
        acc
    } * (1f / coveredPoints.size.toFloat())
}

internal fun orderedPair(firstId: Int, secondId: Int): Pair<Int, Int> =
    if (firstId <= secondId) firstId to secondId else secondId to firstId

internal fun stablePairAxis(firstId: Int, secondId: Int): Vec2 {
    val value = ((orderedPair(firstId, secondId).first * 73) + (orderedPair(firstId, secondId).second * 37)) % 360
    val angle = value.toFloat() * (PI.toFloat() / 180f)
    return Vec2(cos(angle), sin(angle))
}

internal fun resolvePointSpacing(
    points: List<SamplePoint>,
    minSpacing: Float,
    boardWidth: Float,
    boardHeight: Float,
    lockedIndices: Set<Int> = emptySet(),
    iterations: Int = 6,
) {
    repeat(iterations) {
        var adjusted = false
        for (firstIndex in 0 until points.lastIndex) {
            for (secondIndex in firstIndex + 1 until points.size) {
                val first = points[firstIndex]
                val second = points[secondIndex]
                val delta = second.position - first.position
                val currentDistance = delta.magnitude()
                if (currentDistance >= minSpacing - EPSILON) continue

                val direction = when {
                    currentDistance > EPSILON -> delta.normalized()
                    else -> stablePairAxis(firstIndex, secondIndex)
                }
                val overlap = minSpacing - currentDistance
                val firstLocked = firstIndex in lockedIndices
                val secondLocked = secondIndex in lockedIndices
                val firstMove: Vec2
                val secondMove: Vec2
                when {
                    firstLocked && secondLocked -> continue
                    firstLocked -> {
                        firstMove = Vec2(0f, 0f)
                        secondMove = direction * overlap
                    }

                    secondLocked -> {
                        firstMove = direction * -overlap
                        secondMove = Vec2(0f, 0f)
                    }

                    else -> {
                        firstMove = direction * (-overlap * 0.5f)
                        secondMove = direction * (overlap * 0.5f)
                    }
                }

                first.position.add(firstMove)
                second.position.add(secondMove)
                clampPointToBoard(first, boardWidth, boardHeight)
                clampPointToBoard(second, boardWidth, boardHeight)
                adjusted = true
            }
        }
        if (!adjusted) return
    }
}

internal fun clampPointToBoard(point: SamplePoint, boardWidth: Float, boardHeight: Float) {
    point.position.x = point.position.x.coerceIn(0f, boardWidth)
    point.position.y = point.position.y.coerceIn(0f, boardHeight)
}

internal fun midpointOfClosestCoveredPoints(
    first: CircleCandidate,
    second: CircleCandidate,
    points: List<SamplePoint>,
): Vec2 {
    var bestPair: Pair<Vec2, Vec2>? = null
    var bestDistance = Float.MAX_VALUE
    first.coveredPointIndices.forEach { firstIndex ->
        second.coveredPointIndices.forEach { secondIndex ->
            val firstPoint = points[firstIndex].position
            val secondPoint = points[secondIndex].position
            val candidateDistance = distance(firstPoint, secondPoint)
            if (candidateDistance < bestDistance) {
                bestDistance = candidateDistance
                bestPair = firstPoint to secondPoint
            }
        }
    }
    val pair = checkNotNull(bestPair)
    return Vec2((pair.first.x + pair.second.x) * 0.5f, (pair.first.y + pair.second.y) * 0.5f)
}

internal fun closestCoveredPointDistance(
    first: CircleCandidate,
    second: CircleCandidate,
    points: List<SamplePoint>,
): Float {
    var best = Float.MAX_VALUE
    first.coveredPointIndices.forEach { firstIndex ->
        second.coveredPointIndices.forEach { secondIndex ->
            best = min(best, distance(points[firstIndex].position, points[secondIndex].position))
        }
    }
    return best
}

internal fun distance(first: Vec2, second: Vec2): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return sqrt(dx * dx + dy * dy)
}
