package org.mavriksc

import processing.core.PApplet
import processing.video.Movie

class VideoApp : PApplet() {
    var video = Movie(this, "\"C:\\Users\\Owner\\Downloads\\xerath-pent.mp4\"")
    override fun setup() {
        size(640, 480)

    }

    fun movieEvent(m: Movie) {
        video.read()
    }

    override fun draw() {
        if (video.available()) {
            image(video, 0f, 0f)
        }
    }

}

fun main()  {
    System.setProperty("gstreamer.library.path", "D:\\code\\kproc\\lib\\gst\\x64\\bin")
    PApplet.main("org.mavriksc.VideoApp")
}