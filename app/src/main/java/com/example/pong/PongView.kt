package com.example.pong

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class PongView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class State { READY, PLAYING, GAME_OVER }

    private var state: State = State.READY

    private val bgColor = ContextCompat.getColor(context, R.color.pong_bg)
    private val fgColor = ContextCompat.getColor(context, R.color.pong_fg)
    private val accentColor = ContextCompat.getColor(context, R.color.pong_accent)
    private val playerColor = ContextCompat.getColor(context, R.color.pong_player)
    private val aiColor = ContextCompat.getColor(context, R.color.pong_ai)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fgColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fgColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val ball = Ball(0f, 0f, 0f, 0f, 0f)
    private lateinit var playerPaddle: Paddle
    private lateinit var aiPaddle: Paddle

    private var playerScore = 0
    private var aiScore = 0
    private val winningScore = 7

    private var lastFrameNanos: Long = 0L
    private var running = false

    private var fieldTop = 0f
    private var fieldBottom = 0f
    private var fieldLeft = 0f
    private var fieldRight = 0f

    private val baseBallSpeedPxPerSec by lazy {
        resources.displayMetrics.density * 320f
    }

    init {
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setupField(w.toFloat(), h.toFloat())
    }

    private fun setupField(w: Float, h: Float) {
        val density = resources.displayMetrics.density
        val margin = 16f * density
        val scoreArea = 80f * density
        fieldTop = margin + scoreArea
        fieldBottom = h - margin
        fieldLeft = margin
        fieldRight = w - margin

        val paddleWidth = 18f * density
        val paddleHeight = 110f * density
        val midY = (fieldTop + fieldBottom) / 2f
        val playerX = fieldLeft + 40f * density
        val aiX = fieldRight - 40f * density

        playerPaddle = Paddle(playerX, midY, paddleWidth, paddleHeight, playerColor)
        aiPaddle = Paddle(aiX, midY, paddleWidth, paddleHeight, aiColor)

        ball.radius = 12f * density
        resetBall(direction = if ((playerScore + aiScore) % 2 == 0) 1f else -1f)

        scorePaint.textSize = 56f * density
        textPaint.textSize = 22f * density
    }

    private fun resetBall(direction: Float) {
        ball.x = (fieldLeft + fieldRight) / 2f
        ball.y = (fieldTop + fieldBottom) / 2f
        val angle = ((Math.random() * 0.6f) - 0.3f)
        val speed = baseBallSpeedPxPerSec
        ball.vx = direction * speed * Math.cos(angle.toDouble()).toFloat()
        ball.vy = speed * Math.sin(angle.toDouble()).toFloat()
    }

    fun resumeGame() {
        running = true
        lastFrameNanos = 0L
        postInvalidateOnAnimation()
    }

    fun pauseGame() {
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgColor)
        drawNet(canvas)
        drawScores(canvas)
        drawPaddle(canvas, playerPaddle)
        drawPaddle(canvas, aiPaddle)
        drawBall(canvas)
        drawOverlay(canvas)
        if (running) {
            val now = System.nanoTime()
            val dt = if (lastFrameNanos == 0L) 0f
                     else (now - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = now
            if (dt > 0f && dt < 0.1f && state == State.PLAYING) {
                update(dt)
            }
            postInvalidateOnAnimation()
        }
    }

    private fun drawNet(canvas: Canvas) {
        paint.color = fgColor
        paint.alpha = 80
        val midX = (fieldLeft + fieldRight) / 2f
        val dashHeight = 14f * resources.displayMetrics.density
        val gap = 10f * resources.displayMetrics.density
        var y = fieldTop
        while (y < fieldBottom) {
            canvas.drawRect(
                midX - 2f * resources.displayMetrics.density,
                y,
                midX + 2f * resources.displayMetrics.density,
                y + dashHeight,
                paint
            )
            y += dashHeight + gap
        }
        paint.alpha = 255
    }

    private fun drawScores(canvas: Canvas) {
        val midX = (fieldLeft + fieldRight) / 2f
        val topY = fieldTop - 30f * resources.displayMetrics.density
        canvas.drawText(playerScore.toString(), midX - 80f * resources.displayMetrics.density, topY, scorePaint)
        canvas.drawText(aiScore.toString(), midX + 80f * resources.displayMetrics.density, topY, scorePaint)
    }

    private fun drawPaddle(canvas: Canvas, p: Paddle) {
        paint.color = p.color
        canvas.drawRoundRect(p.rect(), 8f, 8f, paint)
    }

    private fun drawBall(canvas: Canvas) {
        paint.color = accentColor
        canvas.drawCircle(ball.x, ball.y, ball.radius, paint)
    }

    private fun drawOverlay(canvas: Canvas) {
        val midX = (fieldLeft + fieldRight) / 2f
        val midY = (fieldTop + fieldBottom) / 2f
        textPaint.color = fgColor
        when (state) {
            State.READY -> {
                textPaint.textSize = 32f * resources.displayMetrics.density
                canvas.drawText("PONG", midX, midY - 30f * resources.displayMetrics.density, textPaint)
                textPaint.textSize = 22f * resources.displayMetrics.density
                canvas.drawText(
                    context.getString(R.string.touch_to_start),
                    midX,
                    midY + 20f * resources.displayMetrics.density,
                    textPaint
                )
            }
            State.GAME_OVER -> {
                textPaint.textSize = 36f * resources.displayMetrics.density
                val msg = if (playerScore >= winningScore)
                    context.getString(R.string.winner_player)
                else
                    context.getString(R.string.winner_ai)
                canvas.drawText(msg, midX, midY, textPaint)
                textPaint.textSize = 22f * resources.displayMetrics.density
                canvas.drawText(
                    context.getString(R.string.tap_to_restart),
                    midX,
                    midY + 40f * resources.displayMetrics.density,
                    textPaint
                )
            }
            State.PLAYING -> {
                // no overlay
            }
        }
    }

    private fun update(dt: Float) {
        moveBall(dt)
        moveAi(dt)
        resolveCollisions()
    }

    private fun moveBall(dt: Float) {
        ball.x += ball.vx * dt
        ball.y += ball.vy * dt
    }

    private fun moveAi(dt: Float) {
        val density = resources.displayMetrics.density
        val maxSpeed = baseBallSpeedPxPerSec * 0.78f
        val deadZone = 14f * density
        val target = ball.y + (Math.random().toFloat() - 0.5f) * 30f * density
        val diff = target - aiPaddle.y
        val move = sign(diff) * min(abs(diff), maxSpeed * dt)
        if (abs(diff) > deadZone) {
            aiPaddle.y = (aiPaddle.y + move)
                .coerceIn(fieldTop + aiPaddle.height / 2f, fieldBottom - aiPaddle.height / 2f)
        }
    }

    private fun resolveCollisions() {
        // Top / bottom wall
        if (ball.y - ball.radius < fieldTop) {
            ball.y = fieldTop + ball.radius
            ball.vy = -ball.vy
        } else if (ball.y + ball.radius > fieldBottom) {
            ball.y = fieldBottom - ball.radius
            ball.vy = -ball.vy
        }

        // Player paddle (left)
        if (ball.vx < 0f && circleIntersectsPaddle(playerPaddle)) {
            val relative = (ball.y - playerPaddle.y) / (playerPaddle.height / 2f)
            val bounceAngle = relative.coerceIn(-1f, 1f) * (Math.PI / 3.0).toFloat() // up to 60deg
            val speed = max(
                baseBallSpeedPxPerSec,
                Math.hypot(ball.vx.toDouble(), ball.vy.toDouble()).toFloat()
            ) * 1.04f
            ball.vx = abs((speed * Math.cos(bounceAngle.toDouble())).toFloat())
            ball.vy = (speed * Math.sin(bounceAngle.toDouble())).toFloat()
            ball.x = playerPaddle.right() + ball.radius
        }

        // AI paddle (right)
        if (ball.vx > 0f && circleIntersectsPaddle(aiPaddle)) {
            val relative = (ball.y - aiPaddle.y) / (aiPaddle.height / 2f)
            val bounceAngle = relative.coerceIn(-1f, 1f) * (Math.PI / 3.0).toFloat()
            val speed = max(
                baseBallSpeedPxPerSec,
                Math.hypot(ball.vx.toDouble(), ball.vy.toDouble()).toFloat()
            ) * 1.04f
            ball.vx = -abs((speed * Math.cos(bounceAngle.toDouble())).toFloat())
            ball.vy = (speed * Math.sin(bounceAngle.toDouble())).toFloat()
            ball.x = aiPaddle.left() - ball.radius
        }

        // Score
        if (ball.x + ball.radius < fieldLeft) {
            aiScore++
            if (aiScore >= winningScore) {
                state = State.GAME_OVER
            }
            resetBall(direction = -1f)
        } else if (ball.x - ball.radius > fieldRight) {
            playerScore++
            if (playerScore >= winningScore) {
                state = State.GAME_OVER
            }
            resetBall(direction = 1f)
        }
    }

    private fun circleIntersectsPaddle(p: Paddle): Boolean {
        val closestX = ball.x.coerceIn(p.left(), p.right())
        val closestY = ball.y.coerceIn(p.top(), p.bottom())
        val dx = ball.x - closestX
        val dy = ball.y - closestY
        return (dx * dx + dy * dy) <= ball.radius * ball.radius
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                when (state) {
                    State.READY -> {
                        state = State.PLAYING
                    }
                    State.GAME_OVER -> {
                        playerScore = 0
                        aiScore = 0
                        state = State.PLAYING
                        resetBall(direction = 1f)
                    }
                    State.PLAYING -> {
                        // Move player paddle to touch position
                        val ty = event.y.coerceIn(
                            fieldTop + playerPaddle.height / 2f,
                            fieldBottom - playerPaddle.height / 2f
                        )
                        playerPaddle.y = ty
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
