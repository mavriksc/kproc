package org.mavriksc.quadTree

import processing.core.PApplet
import processing.core.PGraphics
import java.awt.Color.*
import kotlin.random.Random

// the bug was just with the coloring and that we're coloring all children ending with yellow... and then coming back
// and coloring the parent's points with yellow.

fun main() = PApplet.main("org.mavriksc.quadTree.QuadTreeApp")

class QuadTreeApp : PApplet() {
    private var quadTree: QuadTree<Point>? = null

    override fun settings() {
        size(1600, 1200)
    }

    override fun setup() {
        //background(255)
        quadTree = QuadTree(Rectangle(0, 0, width, height), 4, colors[0]) { it }
        (0..10000).forEach { _ ->
            quadTree?.insert(Point(Random.nextInt(width), Random.nextInt(height)))
        }
//        println(quadTree?.colorDistribution())
//        println(quadTree?.toString())
    }

    override fun draw() {
        quadTree?.reset()
        background(0)

        val x = mouseX - 100//495//Random.nextInt(width)
        val y = mouseY - 100//544//Random.nextInt(height)
        val w = 200//Random.nextInt(width-x)
        val h = 200//Random.nextInt(height-y)
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

class QuadTree<T>(
    val rect: Rectangle, val capacity: Int, val color: Int = 255, private val pointResolver: (T) -> Point
) {
    private val children: Lazy<List<QuadTree<T>>> = lazy { divide() }
    private val items: MutableList<T> = mutableListOf()
    var searched = false

    fun divide(): List<QuadTree<T>> {
        return quadrants.mapIndexed { i, it ->
            QuadTree(
                Rectangle(
                    rect.x + it.x * rect.width / 2, rect.y + it.y * rect.height / 2, rect.width / 2, rect.height / 2
                ), capacity, colors[i], pointResolver
            )
        }
    }

    fun insert(item: T) {
        val point = pointResolver(item)
        if (!point.inRect(rect)) return

        if (items.size < capacity) {
            items.add(item)
            return
        }
        children.value.forEach { it.insert(item) }
        return
    }

    fun query(other: Rectangle): List<T> {
        if (!rect.intersects(other)) return emptyList()
        searched = true
        val found = mutableListOf<T>()
        items.filterTo(found) { pointResolver(it).inRect(other) }
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

    fun colorDistribution(): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        map[color] = items.size
        if (children.isInitialized()) children.value.forEach { child ->
            child.colorDistribution().forEach { (color, count) ->
                map[color] = (map[color] ?: 0) + count
            }
        }
        return map
    }

    override fun toString(): String {
        return "QuadTree(color=$color,\n rect=$rect,\n  items=\n${
            items.map { pointResolver(it) }.joinToString("\n")
        },\n children=${
            if (children.isInitialized()) children.value.joinToString(
                "\n", "\n", "\n"
            ) { it.toString() } else ""
        })"
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
            items.forEach {
                strokeWeight(10f)
                fill(color)
                val point = pointResolver(it)
                point(point.x.toFloat(), point.y.toFloat())
            }
            if (children.isInitialized()) children.value.forEach { it.show(graphics) }
        }
    }
}

data class Rectangle(val x: Int, val y: Int, val width: Int, val height: Int)
data class Point(var x: Int, var y: Int)
