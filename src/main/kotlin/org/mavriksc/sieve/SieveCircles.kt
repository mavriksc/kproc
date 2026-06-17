package org.mavriksc.sieve

import processing.core.PApplet
import processing.core.PGraphics
import kotlin.math.*

fun main(): Unit = PApplet.main("org.mavriksc.sieve.SieveCircles")

private const val UNIT_WIDTH = 10f
private const val ROW_HEIGHT = 20f
private const val TRACK_UNITS_PER_ROW = 90
private const val TRACK_ROWS = 30
private const val LEFT_MARGIN = 42f
private const val TOP_MARGIN = 34f
private const val BOTTOM_MARGIN = 54f
private const val REQUESTED_FRAME_RATE = 1000f
private const val WHEEL_SPEED_UNITS_PER_FRAME = 0.1f
private const val TWO_PI_FLOAT = (PI * 2.0).toFloat()
private const val WINDOW_WIDTH = (LEFT_MARGIN * 2 + TRACK_UNITS_PER_ROW * UNIT_WIDTH).toInt()
private const val WINDOW_HEIGHT = (TOP_MARGIN + TRACK_ROWS * ROW_HEIGHT + BOTTOM_MARGIN).toInt()

class SieveCircles : PApplet() {
    private lateinit var trackLayer: PGraphics
    private val wheelColorCache = mutableMapOf<Int, Int>()
    private val darkColorCache = mutableMapOf<Int, Int>()
    private var sieve = RollingSieve(
        unitsPerRow = TRACK_UNITS_PER_ROW,
        rowCount = TRACK_ROWS,
        speedUnitsPerFrame = WHEEL_SPEED_UNITS_PER_FRAME,
    )
    private var paused = false

    override fun settings() {
        size(WINDOW_WIDTH, WINDOW_HEIGHT)
    }

    override fun setup() {
        frameRate(REQUESTED_FRAME_RATE)
        textAlign(CENTER, CENTER)
        strokeCap(SQUARE)
        rebuildTrackLayer()
    }

    override fun draw() {
        if (!paused) {
            drawDarkenedSegments(sieve.advanceFrame())
        }

        image(trackLayer, 0f, 0f)
        drawWheels()
        drawHud()
    }

    override fun keyPressed() {
        when (key) {
            ' ' -> paused = !paused
            'r', 'R' -> {
                sieve = RollingSieve(
                    unitsPerRow = TRACK_UNITS_PER_ROW,
                    rowCount = TRACK_ROWS,
                    speedUnitsPerFrame = WHEEL_SPEED_UNITS_PER_FRAME,
                )
                paused = false
                rebuildTrackLayer()
            }
        }
    }

    private fun rebuildTrackLayer() {
        trackLayer = createGraphics(WINDOW_WIDTH, WINDOW_HEIGHT)
        trackLayer.beginDraw()
        with(trackLayer) {
            background(14)
            textAlign(CENTER, CENTER)
            for (row in 0 until TRACK_ROWS) {
                val y = trackBaseY(row)
                stroke(54)
                strokeWeight(1f)
                line(LEFT_MARGIN, y, LEFT_MARGIN + TRACK_UNITS_PER_ROW * UNIT_WIDTH, y)

                for (column in 0 until TRACK_UNITS_PER_ROW) {
                    val number = row * TRACK_UNITS_PER_ROW + column + 1
                    val x = LEFT_MARGIN + column * UNIT_WIDTH
                    stroke(35)
                    strokeWeight(1f)
                    line(x, y - 4f, x, y)
                    sieve.darkenedBy(number)?.let { darkenedBy ->
                        drawDarkenedSegment(this, DarkenedSegment(number, darkenedBy))
                    }
                }

                fill(120)
                noStroke()
                textSize(9f)
                text((row + 1).toString(), LEFT_MARGIN - 18f, y - 3f)
            }
        }
        trackLayer.endDraw()
    }

    private fun drawDarkenedSegments(darkenedSegments: List<DarkenedSegment>) {
        if (darkenedSegments.isEmpty()) return
        trackLayer.beginDraw()
        darkenedSegments.forEach { drawDarkenedSegment(trackLayer, it) }
        trackLayer.endDraw()
    }

    private fun drawDarkenedSegment(graphics: PGraphics, darkenedSegment: DarkenedSegment) {
        val segment = darkenedSegment.segment
        val row = (segment - 1) / TRACK_UNITS_PER_ROW
        val column = (segment - 1) % TRACK_UNITS_PER_ROW
        if (row !in 0 until TRACK_ROWS) return

        val x = LEFT_MARGIN + column * UNIT_WIDTH
        val y = trackBaseY(row)
        graphics.noStroke()
        graphics.fill(if (segment == 1) color(35) else darkColor(darkenedSegment.base))
        graphics.rect(x, y - 8f, UNIT_WIDTH, 9f)
    }

    private fun drawWheels() {
        sieve.activeWheels().forEach { wheel ->
            val row = wheel.rowIndex(TRACK_UNITS_PER_ROW)
            if (row !in 0 until TRACK_ROWS) return@forEach

            val x = LEFT_MARGIN + wheel.columnProgress(TRACK_UNITS_PER_ROW) * UNIT_WIDTH
            val trackY = trackBaseY(row)
            val radius = wheel.radiusPixels(UNIT_WIDTH)
            val y = trackY - radius
            val wheelColor = wheelColor(wheel.base)
            val arcWidth = wheel.unitArcRadians(UNIT_WIDTH)
            val arcCenter = PI / 2f + wheel.rotationRadians

            stroke(wheelColor)
            strokeWeight(1.35f)
            noFill()
            ellipse(x, y, radius * 2f, radius * 2f)

            noStroke()
            fill(8f, 8f, 8f, 210f)
            arc(x, y, radius * 2f, radius * 2f, arcCenter - arcWidth / 2f, arcCenter + arcWidth / 2f, PIE)

            fill(245)
            textSize(8f)
            text(wheel.base.toString(), x, min(y, trackY - 5f))
        }
    }

    private fun drawHud() {
        val y = WINDOW_HEIGHT - 28f
        fill(220)
        textSize(11f)
        textAlign(LEFT, CENTER)
        text(
            "bases: ${sieve.spawnedBases().joinToString()}   lowest live: ${sieve.lowestLiveSegment() ?: "-"}   space pause   r reset",
            LEFT_MARGIN,
            y,
        )
        textAlign(CENTER, CENTER)
    }

    private fun trackBaseY(row: Int): Float = TOP_MARGIN + row * ROW_HEIGHT + ROW_HEIGHT - 3f

    private fun wheelColor(base: Int): Int = wheelColorCache.getOrPut(base) {
        colorMode(HSB, 360f, 100f, 100f, 100f)
        val color = color(((base * 47) % 360).toFloat(), 72f, 92f, 100f)
        colorMode(RGB, 255f)
        color
    }

    private fun darkColor(base: Int): Int = darkColorCache.getOrPut(base) {
        colorMode(HSB, 360f, 100f, 100f, 100f)
        val color = color(((base * 47) % 360).toFloat(), 58f, 38f, 100f)
        colorMode(RGB, 255f)
        color
    }
}

internal data class DarkenedSegment(val segment: Int, val base: Int)

internal class RollingSieve(
    private val unitsPerRow: Int,
    private val rowCount: Int,
    private val speedUnitsPerFrame: Float,
) {
    private val maxSegment = unitsPerRow * rowCount
    private val maxWheelBase = floor(sqrt(maxSegment.toFloat())).toInt()
    private val darkenedBy = arrayOfNulls<Int>(maxSegment + 1)
    private val wheels = mutableListOf(RollingWheel(base = 2, speedUnitsPerFrame = speedUnitsPerFrame))
    private val spawnedBases = linkedSetOf(2)

    init {
        darkenedBy[1] = 1
    }

    fun advanceFrame(): List<DarkenedSegment> {
        val darkenedThisFrame = mutableListOf<DarkenedSegment>()
        val activeBeforeAdvance = wheels.toList()
        activeBeforeAdvance.forEach { wheel ->
            val previousDistance = wheel.distanceUnits
            wheel.advance(maxSegment.toFloat())
            darkenedThisFrame += darkenTouchedSegments(wheel.base, previousDistance, wheel.distanceUnits)
        }
        wheels.removeAll { it.distanceUnits >= maxSegment.toFloat() }
        spawnLowestLiveSegmentPassedByAllActiveWheels()
        return darkenedThisFrame
    }

    fun activeWheels(): List<RollingWheel> = wheels.toList()

    fun spawnedBases(): Set<Int> = spawnedBases.toSet()

    fun darkenedBy(segment: Int): Int? = darkenedBy.getOrNull(segment)

    fun lowestLiveSegment(): Int? = (2..maxSegment).firstOrNull { darkenedBy[it] == null && it !in spawnedBases }

    fun lowestSpawnableSegment(): Int? =
        (2..maxWheelBase).firstOrNull { darkenedBy[it] == null && it !in spawnedBases }

    private fun darkenTouchedSegments(base: Int, previousDistance: Float, currentDistance: Float): List<DarkenedSegment> {
        val darkenedThisFrame = mutableListOf<DarkenedSegment>()
        val firstSegment = max(2, floor(previousDistance).toInt() + 1)
        val lastSegment = min(maxSegment, floor(currentDistance).toInt())
        for (segment in firstSegment..lastSegment) {
            if (segment % base == 0 && darkenedBy[segment] == null) {
                darkenedBy[segment] = base
                darkenedThisFrame += DarkenedSegment(segment, base)
            }
        }
        return darkenedThisFrame
    }

    private fun spawnLowestLiveSegmentPassedByAllActiveWheels() {
        val nextBase = lowestSpawnableSegment() ?: return
        val trailingDistance = wheels.minOfOrNull { it.distanceUnits } ?: return
        if (trailingDistance < nextBase.toFloat() + 1f) return

        spawnedBases += nextBase
        wheels += RollingWheel(base = nextBase, speedUnitsPerFrame = speedUnitsPerFrame)
    }
}

internal data class RollingWheel(
    val base: Int,
    private val speedUnitsPerFrame: Float = 0.1f,
    var distanceUnits: Float = base.toFloat(),
    var rotationRadians: Float = 0f,
) {
    private val rotationStepRadians = (speedUnitsPerFrame / base.toFloat()) * TWO_PI_FLOAT

    fun advance(maxDistanceUnits: Float) {
        distanceUnits = min(maxDistanceUnits, distanceUnits + speedUnitsPerFrame)
        rotationRadians = (rotationRadians + rotationStepRadians) % TWO_PI_FLOAT
    }

    fun rowIndex(unitsPerRow: Int): Int = floor(distanceUnits / unitsPerRow.toFloat()).toInt()

    fun columnProgress(unitsPerRow: Int): Float {
        val wrapped = distanceUnits % unitsPerRow.toFloat()
        return if (wrapped == 0f && distanceUnits > 0f) unitsPerRow.toFloat() else wrapped
    }

    fun radiusPixels(unitWidth: Float): Float = (base.toFloat() * unitWidth) / TWO_PI_FLOAT

    fun unitArcRadians(unitWidth: Float): Float = unitWidth / radiusPixels(unitWidth)
}
