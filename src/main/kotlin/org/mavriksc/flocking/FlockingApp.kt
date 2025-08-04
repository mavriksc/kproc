package org.mavriksc.flocking

import org.mavriksc.quadTree.Point
import org.mavriksc.quadTree.QuadTree
import org.mavriksc.quadTree.Rectangle
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
        (0..50).forEach { _ ->
            boids.add(
                Boid(
                    arrayOf(Random().nextFloat(1600f), Random().nextFloat(1200f)),
                    rand2DArray(),
                    rand2DArray()
                )
            )
        }
    }

    override fun draw() {
        background(0)
        val quadTree = QuadTree<Boid>(Rectangle(0, 0, width, height), 2) {
            Point(
                it.position[0].toInt(),
                it.position[1].toInt()
            )
        }
        boids.forEach { quadTree.insert(it) }
        boids.forEach { b ->
            val close = quadTree.query(Rectangle(b.position[0].toInt() - 70, b.position[1].toInt() - 70, 140, 140))
                .filter { it != b }
            b.update(close, width, height)
            //quadTree.show(graphics)
            b.show(graphics)
        }
        //println(frameRate)
    }
}

// we are going to pass a qt in the future to update and will make the list of others there.
class Boid(
    val position: Array<Float>,
    val velocity: Array<Float> = arrayOf(0f, 0f),
    val acceleration: Array<Float> = arrayOf(0f, 0f),
) {
    val maxSpeed = 5f
    val maxForce = 0.3f
    val minSpeed = 1f
    val alignStrength = 0.7f
    val cohesionStrength = 0.5f
    val separationStrength = 5f

    fun update(others: List<Boid>, width: Int, height: Int) {
        val alignment = align(others)
        val separation = separation(others)
        val cohesion = cohesion(others)
        alignment.scale(alignStrength)
        separation.scale(separationStrength)
        cohesion.scale(cohesionStrength)
        acceleration.add(cohesion)
        acceleration.add(alignment)
        acceleration.add(separation)
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
        val steering = others.filter { it != this }.fold(arrayOf(0f, 0f)) { acc, boid ->
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
            strokeWeight(5f)
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
