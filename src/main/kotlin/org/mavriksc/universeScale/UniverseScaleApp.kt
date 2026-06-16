package org.mavriksc.universeScale

import processing.core.PApplet
import java.io.File
import kotlin.math.*

fun main(): Unit = PApplet.main("org.mavriksc.universeScale.UniverseScaleApp")

private const val TARGET_SECONDS = 90f
private const val TARGET_FPS = 60f
private const val INFLATION_PORTION = 0.16
private const val INFLATION_E_FOLDS = 60.0
private const val EARLY_SCALE_FACTOR = 1.0e-30
private const val MIN_EARTH_PIXELS = 2f
private const val HUBBLE_TIME_GYR = 14.52
private const val OMEGA_R = 9.2e-5
private const val OMEGA_M = 0.315
private const val OMEGA_L = 1.0 - OMEGA_M - OMEGA_R
private const val METERS_PER_KM = 1_000.0
private const val METERS_PER_AU = 149_597_870_700.0
private const val METERS_PER_LY = 9.4607304725808e15

private data class Vec2(val x: Float, val y: Float)

private data class CosmicScale(
    val name: String,
    val presentRadiusMeters: Double,
    val detail: String,
    val hue: Float,
    val labelAngleDegrees: Float,
    val finalRadiusPixels: Float? = null,
    val labelGapPixels: Float? = null,
    val farHorizon: Boolean = false,
)

private data class ScaleSample(val ageGyr: Double, val scaleFactor: Double)

class UniverseScaleApp : PApplet() {
    private val model = ExpansionModel()
    private val scales = listOf(
        CosmicScale("Earth", 6_371.0 * METERS_PER_KM, "radius 6,371 km", 194f, 136f, finalRadiusPixels = 2f, labelGapPixels = 30f),
        CosmicScale("Moon", 1_737.0 * METERS_PER_KM, "radius 1,737 km", 220f, 78f, finalRadiusPixels = 1f, labelGapPixels = 56f),
        CosmicScale("Moon orbit", 384_400.0 * METERS_PER_KM, "mean distance 384,400 km", 210f, 194f, finalRadiusPixels = 34f, labelGapPixels = 24f),
        CosmicScale("Sun", 696_340.0 * METERS_PER_KM, "radius 696,340 km", 42f, 8f, finalRadiusPixels = 4f, labelGapPixels = 28f),
        CosmicScale("Inner solar system", 5.2 * METERS_PER_AU, "Jupiter orbit, 5.2 AU", 55f, -24f, finalRadiusPixels = 92f, labelGapPixels = 18f),
        CosmicScale("Heliopause", 100.0 * METERS_PER_AU, "solar wind boundary, about 100 AU", 80f, -6f, finalRadiusPixels = 150f, labelGapPixels = 18f),
        CosmicScale("Oort cloud", 100_000.0 * METERS_PER_AU, "outer edge about 100,000 AU", 108f, 10f, finalRadiusPixels = 235f, labelGapPixels = 18f),
        CosmicScale("Nearby stars", 4.37 * METERS_PER_LY, "Alpha Centauri distance", 145f, 22f, finalRadiusPixels = 330f, labelGapPixels = 20f),
        CosmicScale("Milky Way", 100_000.0 * METERS_PER_LY / 2.0, "diameter about 100,000 ly", 172f, 52f, labelGapPixels = 22f),
        CosmicScale("Andromeda", 2_500_000.0 * METERS_PER_LY, "nearest large galaxy", 250f, 82f, labelGapPixels = 22f),
        CosmicScale("Local Group", 10_000_000.0 * METERS_PER_LY / 2.0, "diameter about 10M ly", 285f, 112f, labelGapPixels = 22f),
        CosmicScale("Observable from Earth", 46_500_000_000.0 * METERS_PER_LY, "radius about 46.5B ly", 320f, 148f, labelGapPixels = 22f),
        CosmicScale("Observable from far horizon", 93_000_000_000.0 * METERS_PER_LY, "another observer's horizon beyond ours", 12f, 202f, labelGapPixels = 26f, farHorizon = true),
    )
    private val minLog = scales.minOf { log10(it.presentRadiusMeters) }
    private val maxLog = scales.maxOf { log10(it.presentRadiusMeters) }
    private var paused = false
    private var elapsedSeconds = (System.getProperty("universeScale.startSeconds")?.toFloatOrNull() ?: 0f).coerceIn(0f, TARGET_SECONDS)
    private var playbackSpeed = 1f
    private var exportFrames = java.lang.Boolean.getBoolean("universeScale.exportFrames")
    private val maxExportFrames = Integer.getInteger("universeScale.maxFrames", -1)
    private val exportDir = File("build/universe-scale-frames")

    override fun settings() {
        fullScreen()
        smooth(8)
    }

    override fun setup() {
        frameRate(TARGET_FPS)
        surface.setTitle("Universe Scale Expansion")
        colorMode(HSB, 360f, 100f, 100f, 100f)
        textFont(createFont("Arial", 16f, true))
        if (exportFrames) exportDir.mkdirs()
    }

    override fun draw() {
        if (!paused) {
            elapsedSeconds = min(TARGET_SECONDS, elapsedSeconds + (1f / TARGET_FPS) * playbackSpeed)
        }
        drawFrame()
        if (exportFrames) {
            exportDir.mkdirs()
            saveFrame(File(exportDir, "universe-scale-######.png").path)
            if (maxExportFrames > 0 && frameCount >= maxExportFrames) exit()
        }
    }

    override fun keyPressed() {
        when (key) {
            ' ' -> paused = !paused
            'r', 'R' -> elapsedSeconds = 0f
            'e', 'E' -> {
                exportFrames = !exportFrames
                if (exportFrames) exportDir.mkdirs()
            }
            '[' -> playbackSpeed = max(0.25f, playbackSpeed * 0.75f)
            ']' -> playbackSpeed = min(4f, playbackSpeed * 1.25f)
        }
    }

    private fun drawFrame() {
        val progress = (elapsedSeconds / TARGET_SECONDS).coerceIn(0f, 1f)
        val scaleFactor = model.scaleFactor(progress.toDouble())
        val ageGyr = model.ageGyr(progress.toDouble())
        val differentiation = smoothStep(0.10f, 0.96f, progress)
        val center = animatedCenter(progress)
        val maxRadius = min(width, height) * 0.43f
        val earlyRadius = min(width, height) * 0.24f

        background(225f, 54f, 5f)
        drawStarField(progress)
        drawHeader(progress, scaleFactor, ageGyr)
        drawRings(progress, differentiation, center, earlyRadius, maxRadius)
        drawScaleRuler()
        drawFooter()
    }

    private fun drawRings(
        progress: Float,
        differentiation: Float,
        center: Vec2,
        earlyRadius: Float,
        maxRadius: Float,
    ) {
        scales.forEachIndexed { index, scale ->
            val finalRadius = finalDisplayRadius(scale, maxRadius)
            val radius = max(1f, lerp(earlyRadius, finalRadius, differentiation))
            val alpha = if (index < 3) 95f else 76f

            noFill()
            stroke(scale.hue, 70f, 95f, alpha)
            strokeWeight(if (scale.farHorizon) 3.2f else if (index >= scales.lastIndex - 1) 2.8f else 1.6f)
            ellipse(center.x, center.y, radius * 2f, radius * 2f)

            val labelPlacement = labelPlacement(scale, center.x, center.y, radius)
            drawLabel(scale, labelPlacement, progress)
        }
    }

    private fun finalDisplayRadius(scale: CosmicScale, maxRadius: Float): Float {
        if (scale.farHorizon) return maxRadius * 1.85f
        scale.finalRadiusPixels?.let { return it }
        val normalized = ((log10(scale.presentRadiusMeters) - minLog) / (maxLog - minLog)).coerceIn(0.0, 1.0)
        val warped = normalized.pow(0.46)
        return (MIN_EARTH_PIXELS + warped * (maxRadius - MIN_EARTH_PIXELS)).toFloat()
    }

    private fun animatedCenter(progress: Float): Vec2 {
        val start = Vec2(width * 0.5f, height * 0.53f)
        val finalAnchor = Vec2(width * 0.18f, height * 0.20f)
        return Vec2(
            lerp(start.x, finalAnchor.x, progress),
            lerp(start.y, finalAnchor.y, progress),
        )
    }

    private data class LabelPlacement(
        val x: Float,
        val y: Float,
        val detailX: Float,
        val detailY: Float,
        val angleDegrees: Float,
    )

    private fun labelPlacement(scale: CosmicScale, x: Float, y: Float, radius: Float): LabelPlacement {
        textSize(13f)
        val titleWidth = textWidth(scale.name)
        textSize(10f)
        val detailWidth = textWidth(scale.detail)
        val maxWidth = max(titleWidth, detailWidth)
        val gap = scale.labelGapPixels ?: 18f
        val preferredAngle = normalizeDegrees(scale.labelAngleDegrees)
        val candidates = buildList {
            add(preferredAngle)
            for (delta in 12..180 step 12) {
                add(normalizeDegrees(preferredAngle + delta))
                add(normalizeDegrees(preferredAngle - delta))
            }
        }

        var best: LabelPlacement? = null
        var bestScore = Float.POSITIVE_INFINITY
        candidates.forEach { angleDegrees ->
            val angle = angleDegrees * (PI.toFloat() / 180f)
            val edgeX = x + cos(angle) * radius
            val edgeY = y + sin(angle) * radius
            val outwardX = cos(angle)
            val outwardY = sin(angle)
            val anchorX = edgeX + outwardX * gap
            val anchorY = edgeY + outwardY * gap

            val labelX = when {
                outwardX > 0.35f -> anchorX
                outwardX < -0.35f -> anchorX - maxWidth
                else -> anchorX - maxWidth * 0.5f
            }
            val labelY = when {
                outwardY > 0.35f -> anchorY + 12f
                outwardY < -0.35f -> anchorY - 8f
                else -> anchorY + 4f
            }
            val detailX = labelX
            val detailY = labelY + 14f

            val overflowLeft = max(0f, 16f - labelX)
            val overflowRight = max(0f, labelX + maxWidth - (width - 16f))
            val overflowTop = max(0f, 28f - (labelY - 13f))
            val overflowBottom = max(0f, detailY - (height - 28f))
            val overflowPenalty = overflowLeft + overflowRight + overflowTop + overflowBottom
            val anglePenalty = angularDistanceDegrees(preferredAngle, angleDegrees) * 0.15f
            val score = overflowPenalty * 1000f + anglePenalty

            if (score < bestScore) {
                bestScore = score
                best = LabelPlacement(
                    x = labelX.coerceIn(16f, width - maxWidth - 16f),
                    y = labelY.coerceIn(40f, height - 42f),
                    detailX = detailX.coerceIn(16f, width - maxWidth - 16f),
                    detailY = detailY.coerceIn(54f, height - 28f),
                    angleDegrees = angleDegrees,
                )
            }
        }
        return checkNotNull(best)
    }

    private fun normalizeDegrees(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }

    private fun angularDistanceDegrees(first: Float, second: Float): Float {
        val delta = kotlin.math.abs(normalizeDegrees(first) - normalizeDegrees(second))
        return min(delta, 360f - delta)
    }

    private fun drawLabel(scale: CosmicScale, placement: LabelPlacement, progress: Float) {
        val labelAlpha = 48f + 52f * smoothStep(0.08f, 0.45f, progress)
        noStroke()
        fill(scale.hue, 42f, 100f, labelAlpha)
        textSize(13f)
        text(scale.name, placement.x, placement.y)
        fill(0f, 0f, 86f, labelAlpha * 0.82f)
        textSize(10f)
        text(scale.detail, placement.detailX, placement.detailY)
    }

    private fun drawHeader(progress: Float, scaleFactor: Double, ageGyr: Double) {
        val era = when {
            progress < INFLATION_PORTION -> "inflation: stylized exponential growth"
            progress < 0.5f -> "radiation and matter dominated expansion"
            progress < 0.82f -> "galaxies and groups emerge"
            else -> "dark-energy era, expansion accelerates"
        }
        fill(0f, 0f, 96f, 96f)
        textSize(26f)
        text("Scale of the universe over time", 32f, 42f)
        textSize(14f)
        fill(0f, 0f, 84f, 90f)
        text("visualized scale factor, not a literal map", 34f, 66f)
        fill(0f, 0f, 96f, 94f)
        textSize(13f)
        val ageText = if (progress < INFLATION_PORTION) "< 10^-32 seconds" else "${format(ageGyr)} billion years after beginning"
        text("$era   |   a(t)=${sci(scaleFactor)}   |   $ageText", 34f, 90f)
    }

    private fun drawScaleRuler() {
        val left = width * 0.13f
        val right = width * 0.87f
        val y = height - 72f
        stroke(0f, 0f, 100f, 38f)
        strokeWeight(1.4f)
        line(left, y, right, y)

        val tickValues = listOf(
            "Earth" to 12_742.0 * METERS_PER_KM,
            "1 AU" to METERS_PER_AU,
            "100 AU" to 100.0 * METERS_PER_AU,
            "1 ly" to METERS_PER_LY,
            "100k ly" to 100_000.0 * METERS_PER_LY,
            "10M ly" to 10_000_000.0 * METERS_PER_LY,
            "46.5B ly" to 46_500_000_000.0 * METERS_PER_LY,
        )
        tickValues.forEachIndexed { index, tick ->
            val normalized = ((log10(tick.second) - minLog) / (maxLog - minLog)).coerceIn(0.0, 1.0)
            val warped = normalized.pow(0.46)
            val x = lerp(left, right, warped.toFloat())
            val tickHeight = if (index % 2 == 0) 13f else 8f
            stroke(0f, 0f, 100f, 54f)
            line(x, y - tickHeight, x, y + tickHeight)
            fill(0f, 0f, 88f, 86f)
            textSize(10f)
            text(tick.first, x - 18f, y + 30f)
        }

        // The ruler is intentionally one-dimensional. Extra guide circles are avoided here because the
        // anchored final view already uses local rings around Earth.
    }

    private fun drawFooter() {
        fill(0f, 0f, 72f, 76f)
        textSize(11f)
        val exportState = if (exportFrames) "export on" else "export off"
        text("space pause/play   r restart   e $exportState   [ ] speed ${format(playbackSpeed.toDouble())}x", 32f, height - 28f)
        text("Frames export as PNGs in build/universe-scale-frames; encode externally when needed.", width * 0.52f, height - 28f)
    }

    private fun drawStarField(progress: Float) {
        randomSeed(19)
        val alpha = 15f + 45f * smoothStep(0.2f, 1f, progress)
        repeat(420) {
            val x = random(width.toFloat())
            val y = random(height.toFloat())
            val brightness = random(62f, 100f)
            stroke(210f, 12f, brightness, alpha * random(0.35f, 1f))
            strokeWeight(random(0.7f, 1.8f))
            point(x, y)
        }
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun format(value: Double): String = "%,.2f".format(value)

    private fun sci(value: Double): String {
        if (value == 0.0) return "0"
        val exponent = kotlin.math.floor(log10(value)).toInt()
        val mantissa = value / 10.0.pow(exponent)
        return "%.2fe%d".format(mantissa, exponent)
    }
}

private class ExpansionModel {
    private val samples: List<ScaleSample> = buildSamples()
    private val maxAge = samples.last().ageGyr

    fun scaleFactor(progress: Double): Double {
        val p = progress.coerceIn(0.0, 1.0)
        if (p <= INFLATION_PORTION) {
            val u = p / INFLATION_PORTION
            return EARLY_SCALE_FACTOR * exp(INFLATION_E_FOLDS * u)
        }
        val u = (p - INFLATION_PORTION) / (1.0 - INFLATION_PORTION)
        val eased = u * u * (3.0 - 2.0 * u)
        val targetAge = eased * maxAge
        return scaleFactorAtAge(targetAge)
    }

    fun ageGyr(progress: Double): Double {
        val p = progress.coerceIn(0.0, 1.0)
        if (p <= INFLATION_PORTION) return 0.0
        val u = (p - INFLATION_PORTION) / (1.0 - INFLATION_PORTION)
        val eased = u * u * (3.0 - 2.0 * u)
        return eased * maxAge
    }

    private fun scaleFactorAtAge(ageGyr: Double): Double {
        if (ageGyr <= 0.0) return samples.first().scaleFactor
        if (ageGyr >= maxAge) return 1.0
        var low = 0
        var high = samples.lastIndex
        while (low < high) {
            val mid = (low + high) / 2
            if (samples[mid].ageGyr < ageGyr) low = mid + 1 else high = mid
        }
        val upper = samples[low]
        val lower = samples[max(0, low - 1)]
        val span = upper.ageGyr - lower.ageGyr
        if (span <= 0.0) return upper.scaleFactor
        val t = ((ageGyr - lower.ageGyr) / span).coerceIn(0.0, 1.0)
        return lower.scaleFactor + (upper.scaleFactor - lower.scaleFactor) * t
    }

    // Expansion references:
    // - NASA WMAP describes inflation as rapid exponential early growth.
    // - Ned Wright's cosmology tutorial gives the scale-factor framing used by Lambda-CDM calculators.
    // This sketch integrates a flat model: H(a)=H0*sqrt(OmegaR/a^4 + OmegaM/a^3 + OmegaL).
    private fun buildSamples(): List<ScaleSample> {
        val result = mutableListOf(ScaleSample(0.0, 1.0e-9))
        var age = 0.0
        var previousA = 1.0e-9
        val steps = 14_000
        for (i in 1..steps) {
            val x = i.toDouble() / steps.toDouble()
            val a = 10.0.pow(-9.0 + 9.0 * x)
            val midA = sqrt(previousA * a)
            val da = a - previousA
            val h = sqrt(OMEGA_R / midA.pow(4.0) + OMEGA_M / midA.pow(3.0) + OMEGA_L)
            age += HUBBLE_TIME_GYR * da / (midA * h)
            result += ScaleSample(age, a)
            previousA = a
        }
        return result
    }
}
