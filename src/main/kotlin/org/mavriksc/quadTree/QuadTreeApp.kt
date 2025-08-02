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
    }

    override fun draw() {
        background(0)
        if(mousePressed) quadTree?.insert(Point(mouseX,mouseY))
        //quadTree?.insert(Point(Random.nextInt(width), Random.nextInt(height)))
        showTree(quadTree!!)
        //Thread.sleep(1000)
    }

    fun showTree(tree: QuadTree) {
        tree.show(graphics)
    }
}

private val colors = arrayOf(RED.rgb, BLUE.rgb, GREEN.rgb, YELLOW.rgb)
private val quadrants: Array<Point> = arrayOf(Point(0, 0), Point(1, 0), Point(0, 1), Point(1, 1))
class QuadTree(val rect: Rectangle, val capacity: Int, val color: Int = 255) {
    private val children: Lazy<List<QuadTree>> = lazy { divide() }
    private val points: MutableList<Point> = mutableListOf()
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

    fun insert(point: Point): Boolean {
        if (!point.inRect(rect)) return false

        if (points.size < capacity) {
            points.add(point)
            return true
        }
        return children.value.map { it.insert(point) }.any { true }
    }

    private fun Point.inRect(rect: Rectangle): Boolean {
        return this.x >= rect.x && this.x <= rect.x + rect.width && this.y >= rect.y && this.y <= rect.y + rect.height
    }

    fun show(graphics: PGraphics) {
        with(graphics) {
            stroke(color)
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