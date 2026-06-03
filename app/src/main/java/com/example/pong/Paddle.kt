package com.example.pong

import android.graphics.RectF

data class Paddle(
    var x: Float,
    var y: Float,
    val width: Float,
    val height: Float,
    val color: Int
) {
    fun top(): Float = y - height / 2f
    fun bottom(): Float = y + height / 2f
    fun left(): Float = x - width / 2f
    fun right(): Float = x + width / 2f

    fun rect(): RectF = RectF(left(), top(), right(), bottom())

    fun contains(px: Float, py: Float): Boolean {
        return px in left()..right() && py in top()..bottom()
    }
}
