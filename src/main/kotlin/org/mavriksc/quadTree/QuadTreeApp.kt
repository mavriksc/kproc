package org.mavriksc.quadTree

import processing.core.PApplet
import processing.core.PGraphics
import java.awt.Color.*
import kotlin.random.Random

class QuadTreeApp : PApplet() {
    private var quadTree: QuadTree? = null

    override fun settings() {
        size(1600, 1200)
    }

    override fun setup() {
        background(255)
        quadTree = QuadTree(Rectangle(0, 0, width, height), 4)
        (0..500).forEach { _ ->
            quadTree?.insert(Point(Random.nextInt(width), Random.nextInt(height)))
        }
    }

    override fun draw() {
        quadTree?.reset()
        background(0)

        val x = mouseX - 400//495//Random.nextInt(width)
        val y = mouseY - 200//544//Random.nextInt(height)
        val w = 800//Random.nextInt(width-x)
        val h = 400//Random.nextInt(height-y)
        val inside = quadTree?.query(Rectangle(x, y, w, h)) ?: emptyList()

        quadTree?.show(graphics)
        strokeWeight(4f)
        stroke(PINK.rgb)
        noFill()
        rect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
        inside.forEach {
            strokeWeight(10f)
            fill(PINK.rgb)
            point(it.x.toFloat(), it.y.toFloat())
        }
    }
}

private val colors = arrayOf(RED.rgb, BLUE.rgb, GREEN.rgb, YELLOW.rgb)
private val quadrants: Array<Point> = arrayOf(Point(0, 0), Point(1, 0), Point(0, 1), Point(1, 1))

class QuadTree(val rect: Rectangle, val capacity: Int, val color: Int = 255) {
    private val children: Lazy<List<QuadTree>> = lazy { divide() }
    private val points: MutableList<Point> = mutableListOf()
    var searched = false
    fun divide(): List<QuadTree> {
        return quadrants.mapIndexed { i, it ->
            QuadTree(
                Rectangle(
                    rect.x + it.x * rect.width / 2,
                    rect.y + it.y * rect.height / 2,
                    rect.width / 2,
                    rect.height / 2
                ), capacity, colors[i]
            )
        }
    }

    fun insert(point: Point) {
        if (!point.inRect(rect)) return

        if (points.size < capacity) {
            points.add(point)
            return
        }
        children.value.forEach { it.insert(point) }
        return
    }

    fun query(other: Rectangle): List<Point> {
        if (!rect.intersects(other)) return emptyList()
        searched = true
        val found = mutableListOf<Point>()
        points.filterTo(found) { it.inRect(other) }
        if (children.isInitialized()) children.value.forEach { found.addAll(it.query(other)) }
        return found
    }
    fun reset() {
        searched = false
        if (children.isInitialized()) children.value.forEach { it.reset() }
    }

    private fun Point.inRect(rect: Rectangle): Boolean {
        return this.x >= rect.x && this.x <= rect.x + rect.width && this.y >= rect.y && this.y <= rect.y + rect.height
    }

    fun Rectangle.intersects(other: Rectangle): Boolean {
        return this.x < other.x + other.width && this.x + this.width > other.x && this.y < other.y + other.height && this.y + this.height > other.y
    }

    fun show(graphics: PGraphics) {
        with(graphics) {
            if (searched) {
                stroke(CYAN.rgb)
            } else {
                stroke(color)
            }
            strokeWeight(1f)
            noFill()
            rect(rect.x.toFloat(), rect.y.toFloat(), rect.width.toFloat(), rect.height.toFloat())
            if (children.isInitialized()) children.value.forEach { it.show(graphics) }
            points.forEach {
                strokeWeight(10f)
                fill(color)
                point(it.x.toFloat(), it.y.toFloat())
            }
        }
    }


}

data class Rectangle(val x: Int, val y: Int, val width: Int, val height: Int)
data class Point(val x: Int, val y: Int)


fun main() = PApplet.main("org.mavriksc.quadTree.QuadTreeApp")