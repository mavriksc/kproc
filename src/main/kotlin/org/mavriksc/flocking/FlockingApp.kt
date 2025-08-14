package org.mavriksc.flocking

import org.mavriksc.quadTree.Point
import org.mavriksc.quadTree.QuadTree
import org.mavriksc.quadTree.Rectangle
import processing.core.PApplet
import processing.core.PConstants.CLOSE
import processing.core.PGraphics
import java.awt.Color.RED
import java.util.*
import kotlin.math.*

//DONE: parallelize will also need to batch update instead of updating and calculating based on that new value.
// is not faster until a very large number of boids is present.
//  the one shot calculation of the forces seems off. create tests against the individual calculations and verify
// created test and fixed the one shot calculation.

fun main() =  PApplet.main("org.mavriksc.flocking.FlockingApp")

private const val visionSize = 100f
private const val visionForward = 0.7f
private const val drawVision = true
private const val numBoids = 300

class FlockingApp : PApplet() {
    val boids = mutableListOf<Boid>()
    override fun settings() {
        //size(2000, 1300)
        fullScreen()
    }

    override fun setup() {
        background(255)
        (0..numBoids).forEach { _ ->
            boids.add(
                Boid(
                    arrayOf(Random().nextFloat(width.toFloat()), Random().nextFloat(height.toFloat())),
                    rand2DArray(),
                    rand2DArray()
                )
            )
        }
    }

    override fun draw() {
        background(0)
        val quadTree = QuadTree<Boid>(Rectangle(0, 0, width, height), 5) {
            Point(
                it.position[0].toInt(),
                it.position[1].toInt()
            )
        }
        boids.forEach { quadTree.insert(it) }
        boids.parallelStream().forEach { b ->
            // forward looking instead of round looking
            val vision = arrayOf(b.velocity[0], b.velocity[1])
            vision.scale((visionSize * visionForward) / vision.mag())
            vision.add(b.position)
            vision.sub(arrayOf(visionSize, visionSize))
            val close = quadTree.query(
                Rectangle(
                    vision[0].toInt(),
                    vision[1].toInt(),
                    (visionSize * 2).toInt(),
                    (visionSize * 2).toInt()
                )
            )
                .filter { it != b }
            b.calcForces(close)
        }
        boids.forEach {
            it.update(width, height)
            it.show(graphics)
        }
        //println(frameRate)
        //quadTree.show(graphics)
    }
}

class Boid(
    val position: Array<Float>,
    val velocity: Array<Float> = arrayOf(0f, 0f),
    val acceleration: Array<Float> = arrayOf(0f, 0f),
) {
    val maxSpeed = 4f
    val maxForce = 0.2f
    val minSpeed = 2f
    val alignStrength = 1.7f
    val cohesionStrength = 0.5f
    val separationStrength = 4f

    fun update(width: Int, height: Int) {
        velocity.add(acceleration)
        val clippedVelocity = min(maxSpeed, max(minSpeed, velocity.mag()))
        velocity.scale(clippedVelocity / velocity.mag())
        position.add(velocity)
        wrap(width, height)
    }

    fun calcForces(others: List<Boid>) {
        val forces = allThree(others)
        val alignment = forces[0]
        val cohesion = forces[1]
        val separation = forces[2]
        alignment.scale(alignStrength)
        cohesion.scale(cohesionStrength)
        separation.scale(separationStrength)
        acceleration.add(alignment)
        acceleration.add(cohesion)
        acceleration.add(separation)
        val clippedAcceleration = min(maxForce, acceleration.mag())
        acceleration.scale(clippedAcceleration / acceleration.mag())
    }

    fun wrap(width: Int, height: Int) {
        when {
            position[0] < 0 -> position[0] += width.toFloat()
            position[0] > width.toFloat() -> position[0] -= width.toFloat()
        }
        when {
            position[1] < 0 -> position[1] += height.toFloat()
            position[1] > height.toFloat() -> position[1] -= height.toFloat()
        }
    }

    fun allThree(others: List<Boid>): Array<Array<Float>> {
        if (others.isEmpty()) return arrayOf(arrayOf(0f, 0f), arrayOf(0f, 0f), arrayOf(0f, 0f))
        val forces = others.fold(
            arrayOf(arrayOf(0f, 0f), arrayOf(0f, 0f), arrayOf(0f, 0f))
        ) { acc, boid ->
            acc[0].add(boid.velocity)
            acc[1].add(boid.position)
            val separation = arrayOf(0f, 0f)
            separation.add(this.position)
            separation.sub(boid.position)
            separation.scale(1 / separation.mag())
            acc[2].add(separation)
            acc
        }
        forces[0].scale(1 / others.size.toFloat())
        forces[1].scale(1 / others.size.toFloat())
        forces[1].sub(this.position)
        return forces
    }

    fun align(others: List<Boid>): Array<Float> {
        if (others.isEmpty()) return arrayOf(0f, 0f)
        val steering = others.fold(arrayOf(0f, 0f)) { acc, boid ->
            acc.add(boid.velocity)
            acc
        }
        steering.scale(1 / others.size.toFloat())
        return steering
    }

    fun cohesion(others: List<Boid>): Array<Float> {
        if (others.isEmpty()) return arrayOf(0f, 0f)
        val steering = others.fold(arrayOf(0f, 0f)) { acc, boid ->
            acc.add(boid.position)
            acc
        }
        steering.scale(1 / others.size.toFloat())
        steering.sub(this.position)
        return steering
    }

    fun separation(others: List<Boid>): Array<Float> {
        if (others.isEmpty()) return arrayOf(0f, 0f)
        val steering = others.fold(arrayOf(0f, 0f)) { acc, boid ->
            val separation = arrayOf(0f, 0f)
            separation.add(this.position)
            separation.sub(boid.position)
            separation.scale(1 / separation.mag())
            acc.add(separation)
            acc
        }
        return steering
    }

    fun show(graphics: PGraphics) {
        with(graphics) {
            strokeWeight(1f)
            stroke(255)
            fill(255)

            val direction = atan2(velocity[1], velocity[0])
            val x = position[0]
            val y = position[1]

            val r = 10f
            val base = 3f
            beginShape()
            vertex(x + r * cos(direction), y + r * sin(direction))
            vertex(
                (x + base * cos(direction - PI / 2)).toFloat(),
                (y + base * sin(direction - PI / 2)).toFloat()
            )
            vertex(
                (x + base * cos(direction + PI / 2)).toFloat(),
                (y + base * sin(direction + PI / 2)).toFloat()
            )
            endShape(CLOSE)
            if (drawVision) {

                val vision = arrayOf(velocity[0], velocity[1])
                vision.scale((visionSize * visionForward) / vision.mag())
                vision.add(position)
                noFill()
                stroke(RED.rgb)
                strokeWeight(1f)
                ellipse(vision[0], vision[1], visionSize * 2, visionSize * 2)
            }
        }
    }
}

fun Array<Float>.add(other: Array<Float>) = this.forEachIndexed { i, fl -> this[i] += other[i] }
fun Array<Float>.sub(other: Array<Float>) = this.forEachIndexed { i, fl -> this[i] -= other[i] }
fun Array<Float>.mag(): Float = sqrt(this.magSq())
fun Array<Float>.magSq(): Float = this.fold(0f) { acc, fl -> acc + fl * fl }
fun Array<Float>.scale(s: Float) = this.forEachIndexed { i, fl -> this[i] = fl * s }
fun rand2DArray(): Array<Float> {
    val rand = arrayOf(Random().nextFloat(-1f, 1f), Random().nextFloat(-1f, 1f))
    rand.scale(1 / rand.mag())
    return rand
}

fun boidTestForceCalc() {
    val h = 1600f
    val w = 1600f
    val boids = (0..numBoids).map { _ ->
        Boid(
            arrayOf(Random().nextFloat(w), Random().nextFloat(h)),
            rand2DArray(),
            rand2DArray()
        )
    }
    (0..100).forEach { _ ->
        val qt = QuadTree<Boid>(Rectangle(0, 0, w.toInt(), h.toInt()), 5) {
            Point(
                it.position[0].toInt(),
                it.position[1].toInt()
            )
        }
        boids.forEach { qt.insert(it) }
        boids.parallelStream().forEach { b ->
            // forward looking instead of round looking
            val vision = arrayOf(b.velocity[0], b.velocity[1])
            vision.scale((visionSize * visionForward) / vision.mag())
            vision.add(b.position)
            vision.sub(arrayOf(visionSize, visionSize))
            val close = qt.query(
                Rectangle(
                    vision[0].toInt(),
                    vision[1].toInt(),
                    (visionSize * 2).toInt(),
                    (visionSize * 2).toInt()
                )
            )
                .filter { it != b }
            b.calcForces(close)
        }
        boids.forEach { it.update(h.toInt(), w.toInt()) }
    }
    val qt = QuadTree<Boid>(Rectangle(0, 0, w.toInt(), h.toInt()), 5) {
        Point(
            it.position[0].toInt(),
            it.position[1].toInt()
        )
    }
    boids.forEach { qt.insert(it) }
    val maxNeighborBoid = boids.maxBy { b ->
        val vision = arrayOf(b.velocity[0], b.velocity[1])
        vision.scale((visionSize * visionForward) / vision.mag())
        vision.add(b.position)
        vision.sub(arrayOf(visionSize, visionSize))
        val close = qt.query(
            Rectangle(
                vision[0].toInt(),
                vision[1].toInt(),
                (visionSize * 2).toInt(),
                (visionSize * 2).toInt()
            )
        )
            .filter { it != b }
        close.size
    }
    val vision = arrayOf(maxNeighborBoid.velocity[0], maxNeighborBoid.velocity[1])
    vision.scale((visionSize * visionForward) / vision.mag())
    vision.add(maxNeighborBoid.position)
    vision.sub(arrayOf(visionSize, visionSize))
    val close = qt.query(
        Rectangle(
            vision[0].toInt(),
            vision[1].toInt(),
            (visionSize * 2).toInt(),
            (visionSize * 2).toInt()
        )
    )
        .filter { it != maxNeighborBoid }


    val allThree = maxNeighborBoid.allThree(close)
    val alignment = maxNeighborBoid.align(close)
    val separation = maxNeighborBoid.separation(close)
    val cohesion = maxNeighborBoid.cohesion(close)
    assert(allThree[0].contentEquals(alignment))
    assert(allThree[1].contentEquals(separation))
    assert(allThree[2].contentEquals(cohesion))
    println("alignement:allthree: ${allThree[0].contentToString()} individual: ${alignment.contentToString()} ")
    println("cohesion:allthree: ${allThree[1].contentToString()} individual: ${cohesion.contentToString()} ")
    println("separation:allthree: ${allThree[2].contentToString()} individual: ${separation.contentToString()} ")

}