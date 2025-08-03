package org.mavriksc.flocking

import processing.core.PApplet
import processing.core.PGraphics
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

fun main() = PApplet.main("org.mavriksc.flocking.FlockingApp")

class FlockingApp : PApplet() {
    //val qt = QuadTree<Boid>(Rectangle(0, 0, width, height), 4,RED.rgb) { it.position }
    val boids = mutableListOf<Boid>()
    override fun settings() {
        size(1600, 1200)
    }

    override fun setup() {
        background(255)
        (0..100).forEach { _ ->
            val position = rand2DArray()
            position.scale(Random().nextFloat(400f))
            boids.add(
                Boid(
                    position,
                    rand2DArray(),
                    rand2DArray()
                )
            )
        }
    }

    override fun draw() {
        background(0)
        boids.forEach {
            it.update(boids, width, height)
            it.show(graphics)
        }

    }
}

// we are going to pass a qt in the future to update and will make the list of others there.
class Boid(
    val position: Array<Float>,
    val velocity: Array<Float> = arrayOf(0f, 0f),
    val acceleration: Array<Float> = arrayOf(0f, 0f),
) {
    val maxSpeed = 2f
    val maxForce = 0.1f
    val minSpeed = 0.1f

    fun update(others: List<Boid>, width: Int, height: Int) {
        acceleration.add(cohesion(others))
        acceleration.add(align(others))
        acceleration.add(separation(others))
        val clippedAcceleration = min(maxForce, acceleration.mag())
        acceleration.scale(clippedAcceleration / acceleration.mag())
        velocity.add(acceleration)
        val clippedVelocity = min(maxSpeed, max(minSpeed, velocity.mag()))
        velocity.scale(clippedVelocity / velocity.mag())
        position.add(velocity)
        wrap(width, height)

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

    fun align(boids: List<Boid>): Array<Float> {
        val steering = boids.fold(arrayOf(0f, 0f)) { acc, boid ->
            acc.add(boid.velocity)
            acc
        }
        steering.sub(this.velocity)
        steering.scale(1 / boids.size.toFloat())
        return steering
    }

    fun cohesion(boids: List<Boid>): Array<Float> {
        val steering = boids.fold(arrayOf(0f, 0f)) { acc, boid ->
            acc.add(boid.position)
            acc
        }
        steering.sub(this.position)
        steering.scale(1 / boids.size.toFloat())
        steering.sub(this.position)
        return steering
    }

    fun separation(boids: List<Boid>): Array<Float> {
        val steering = boids.filter { it != this }.fold(arrayOf(0f, 0f)) { acc, boid ->
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
            strokeWeight(16f)
            stroke(255)
            point(position[0], position[1])
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
