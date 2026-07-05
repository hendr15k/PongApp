package com.example.pong

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

class PongView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class State { READY, PLAYING, GAME_OVER }

    private var state: State = State.READY
        set(value) {
            field = value
            updateAccessibility()
        }

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
        set(value) {
            field = value
            updateAccessibility()
        }
    private var aiScore = 0
        set(value) {
            field = value
            updateAccessibility()
        }
    private val winningScore = 7

    private var lastFrameNanos: Long = 0L
    private var running = false

    private var fieldTop = 0f
    private var fieldBottom = 0f
    private var fieldLeft = 0f
    private var fieldRight = 0f

    private var paddleCornerRadius = 0f
    private var touchSlop = 0f
    private val tapMaxDurationMs = 200L

    private val baseBallSpeedPxPerSec by lazy {
        resources.displayMetrics.density * 320f
    }

    // AI state
    private var aiTargetY = 0f
    private var aiPredictedY = 0f
    private var aiLastPredictTime = 0L
    private val aiPredictIntervalMs = 90L
    private val aiReactionMs = 110L
    private val aiAimErrorDp = 26f
    private val aiMaxSpeedFactor = 0.86f
    private val aiCenterReturn = 0.6f

    // Touch state
    private var touchActive = false
    private var touchDownTime = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchMoved = false
    private var playerTargetY = 0f
    private val playerFollowLerp = 18f  // higher = snappier

    init {
        isFocusable = true
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        updateAccessibility()
    }

    private fun updateAccessibility() {
        val msg = when (state) {
            State.READY -> "${context.getString(R.string.app_name)}. ${context.getString(R.string.touch_to_start)}"
            State.PLAYING -> context.getString(R.string.game_running, playerScore, aiScore)
            State.GAME_OVER -> {
                val winner = if (playerScore >= winningScore) context.getString(R.string.winner_player) else context.getString(R.string.winner_ai)
                "${context.getString(R.string.game_over)}. $winner. ${context.getString(R.string.tap_to_restart)}"
            }
        }
        contentDescription = msg
        announceForAccessibility(msg)
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
        paddleCornerRadius = 8f * density

        playerPaddle = Paddle(playerX, midY, paddleWidth, paddleHeight, playerColor)
        aiPaddle = Paddle(aiX, midY, paddleWidth, paddleHeight, aiColor)
        playerTargetY = midY
        aiTargetY = midY

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
        canvas.drawRoundRect(p.rect(), paddleCornerRadius, paddleCornerRadius, paint)
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
                canvas.drawText(context.getString(R.string.app_name), midX, midY - 30f * resources.displayMetrics.density, textPaint)
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
            State.PLAYING -> Unit
        }
    }

    private fun update(dt: Float) {
        movePlayer(dt)
        moveBallSubstepped(dt)
        moveAi(dt)
    }

    private fun movePlayer(dt: Float) {
        // Smooth follow toward the latest touch target (or paddle center when idle)
        val lerpFactor = 1f - Math.exp((-playerFollowLerp * dt).toDouble()).toFloat()
        playerPaddle.y = playerPaddle.y + (playerTargetY - playerPaddle.y) * lerpFactor
        playerPaddle.y = playerPaddle.y.coerceIn(
            fieldTop + playerPaddle.height / 2f,
            fieldBottom - playerPaddle.height / 2f
        )
    }

    private fun moveBallSubstepped(dt: Float) {
        val dx = ball.vx * dt
        val dy = ball.vy * dt
        val step = ball.radius * 0.6f
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val subSteps = max(1, ceil(dist / step).toInt())
        val sdt = dt / subSteps
        for (i in 0 until subSteps) {
            ball.x += ball.vx * sdt
            ball.y += ball.vy * sdt
            if (resolveCollisionsStep()) return
        }
    }

    private fun resolveCollisionsStep(): Boolean {
        // Walls
        if (ball.y - ball.radius < fieldTop) {
            ball.y = fieldTop + ball.radius
            ball.vy = -ball.vy
        } else if (ball.y + ball.radius > fieldBottom) {
            ball.y = fieldBottom - ball.radius
            ball.vy = -ball.vy
        }

        // Player paddle
        if (ball.vx < 0f && circleHitsPaddle(playerPaddle)) {
            bounceOffPaddle(playerPaddle, pushOut = +1f)
            return true
        }
        // AI paddle
        if (ball.vx > 0f && circleHitsPaddle(aiPaddle)) {
            bounceOffPaddle(aiPaddle, pushOut = -1f)
            return true
        }

        // Score
        if (ball.x + ball.radius < fieldLeft) {
            aiScore++
            if (aiScore >= winningScore) state = State.GAME_OVER
            resetBall(direction = -1f)
            return true
        } else if (ball.x - ball.radius > fieldRight) {
            playerScore++
            if (playerScore >= winningScore) state = State.GAME_OVER
            resetBall(direction = 1f)
            return true
        }
        return false
    }

    /**
     * Test if the ball circle intersects the given rounded-rect paddle.
     * Uses exact closest-point-on-rounded-rect math (handles straight edges
     * and quarter-circle corners), which is more accurate than the previous
     * AABB clamp for the rounded ends.
     */
    private fun circleHitsPaddle(p: Paddle): Boolean {
        val left = p.left()
        val right = p.right()
        val top = p.top()
        val bottom = p.bottom()
        val r = paddleCornerRadius

        val cx = ball.x.coerceIn(left, right)
        val cy = ball.y.coerceIn(top, bottom)

        // Determine which region of the rounded rect the clamped point lies in.
        val onLeftBand = (cx - left) <= r
        val onRightBand = (right - cx) <= r
        val onTopBand = (cy - top) <= r
        val onBottomBand = (bottom - cy) <= r

        val closestX: Float
        val closestY: Float
        when {
            onTopBand && onLeftBand -> {
                val ax = left + r; val ay = top + r
                val ddx = ball.x - ax; val ddy = ball.y - ay
                val d = hypot(ddx.toDouble(), ddy.toDouble()).toFloat()
                if (d <= r) return true   // already overlapping the corner arc
                closestX = ax + ddx / d * r
                closestY = ay + ddy / d * r
            }
            onTopBand && onRightBand -> {
                val ax = right - r; val ay = top + r
                val ddx = ball.x - ax; val ddy = ball.y - ay
                val d = hypot(ddx.toDouble(), ddy.toDouble()).toFloat()
                if (d <= r) return true
                closestX = ax + ddx / d * r
                closestY = ay + ddy / d * r
            }
            onBottomBand && onLeftBand -> {
                val ax = left + r; val ay = bottom - r
                val ddx = ball.x - ax; val ddy = ball.y - ay
                val d = hypot(ddx.toDouble(), ddy.toDouble()).toFloat()
                if (d <= r) return true
                closestX = ax + ddx / d * r
                closestY = ay + ddy / d * r
            }
            onBottomBand && onRightBand -> {
                val ax = right - r; val ay = bottom - r
                val ddx = ball.x - ax; val ddy = ball.y - ay
                val d = hypot(ddx.toDouble(), ddy.toDouble()).toFloat()
                if (d <= r) return true
                closestX = ax + ddx / d * r
                closestY = ay + ddy / d * r
            }
            else -> {
                // On a straight edge or inside the rect — clamp is the closest point.
                closestX = cx
                closestY = cy
            }
        }

        val ddx = ball.x - closestX
        val ddy = ball.y - closestY
        return (ddx * ddx + ddy * ddy) <= ball.radius * ball.radius
    }

    private fun bounceOffPaddle(p: Paddle, pushOut: Float) {
        val relative = ((ball.y - p.y) / (p.height / 2f)).coerceIn(-1f, 1f)
        val bounceAngle = relative * (Math.PI / 3.0).toFloat()
        val currentSpeed = hypot(ball.vx.toDouble(), ball.vy.toDouble()).toFloat()
        val speed = max(baseBallSpeedPxPerSec, currentSpeed) * 1.04f
        val dirX = sign(pushOut)
        ball.vx = dirX * abs((speed * Math.cos(bounceAngle.toDouble())).toFloat())
        ball.vy = (speed * Math.sin(bounceAngle.toDouble())).toFloat()
        // Push out so the ball doesn't get stuck inside the paddle.
        if (pushOut > 0f) ball.x = p.right() + ball.radius
        else ball.x = p.left() - ball.radius
    }

    /**
     * Trace the ball's trajectory (ignoring paddles) to predict its Y when
     * it reaches the AI's X. Bounces off the top/bottom walls.
     * Returns Float.NaN if the ball is moving away from the AI.
     */
    private fun predictBallYAt(targetX: Float): Float {
        if (ball.vx == 0f) return Float.NaN
        val goingRight = ball.vx > 0f
        if (goingRight && targetX <= ball.x) return Float.NaN
        if (!goingRight && targetX >= ball.x) return Float.NaN

        var x = ball.x
        var y = ball.y
        var vx = ball.vx
        var vy = ball.vy
        val maxIterations = 240
        for (i in 0 until maxIterations) {
            // Step size: move toward the target X.
            val tX = if (vx > 0f) (targetX - x) / vx else (targetX - x) / vx
            // Cap step to a safe size so wall bounces stay accurate.
            val safeStep = min(abs(tX), (ball.radius * 4f) / max(0.0001f, abs(vx)))
            val stepDt = safeStep
            x += vx * stepDt
            y += vy * stepDt
            if (y - ball.radius < fieldTop) {
                y = fieldTop + ball.radius
                vy = -vy
            } else if (y + ball.radius > fieldBottom) {
                y = fieldBottom - ball.radius
                vy = -vy
            }
            val reached = if (goingRight) x >= targetX else x <= targetX
            if (reached) return y
        }
        return y
    }

    private fun moveAi(dt: Float) {
        val now = System.nanoTime() / 1_000_000L
        val density = resources.displayMetrics.density

        // Re-predict the ball position periodically (or when direction changes).
        if (now - aiLastPredictTime > aiPredictIntervalMs) {
            val predicted = predictBallYAt(aiPaddle.x)
            aiLastPredictTime = now
            if (predicted.isNaN()) {
                // Ball is moving away — drift back to center.
                val midY = (fieldTop + fieldBottom) / 2f
                aiTargetY += (midY - aiTargetY) * aiCenterReturn * dt * 3f
            } else {
                // Add a small per-shot aim error so the AI is beatable.
                val err = (Math.random().toFloat() - 0.5f) * 2f * aiAimErrorDp * density
                aiPredictedY = (predicted + err)
                    .coerceIn(fieldTop + aiPaddle.height / 2f, fieldBottom - aiPaddle.height / 2f)
            }
        }

        // Reaction delay: don't snap to the new prediction instantly.
        val targetForFrame = if (now - aiLastPredictTime > aiReactionMs) aiPredictedY
                              else aiTargetY

        val maxSpeed = baseBallSpeedPxPerSec * aiMaxSpeedFactor
        val diff = targetForFrame - aiPaddle.y
        val move = sign(diff) * min(abs(diff), maxSpeed * dt)
        aiPaddle.y = (aiPaddle.y + move)
            .coerceIn(fieldTop + aiPaddle.height / 2f, fieldBottom - aiPaddle.height / 2f)
        aiTargetY = aiPaddle.y
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                touchDownTime = System.currentTimeMillis()
                touchStartX = event.x
                touchStartY = event.y
                touchMoved = false
                // While playing, the initial down position also moves the paddle.
                if (state == State.PLAYING) updatePlayerTarget(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) return false
                if (!touchMoved) {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) touchMoved = true
                }
                if (touchMoved && state == State.PLAYING) {
                    updatePlayerTarget(event.y)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!touchActive) return false
                touchActive = false
                val duration = System.currentTimeMillis() - touchDownTime
                val isTap = !touchMoved && duration <= tapMaxDurationMs
                if (isTap) {
                    when (state) {
                        State.READY -> state = State.PLAYING
                        State.GAME_OVER -> {
                            playerScore = 0
                            aiScore = 0
                            state = State.PLAYING
                            resetBall(direction = 1f)
                        }
                        State.PLAYING -> {
                            // A tap in PLAYING also moves the paddle to the tap location.
                            updatePlayerTarget(event.y)
                        }
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updatePlayerTarget(ty: Float) {
        playerTargetY = ty.coerceIn(
            fieldTop + playerPaddle.height / 2f,
            fieldBottom - playerPaddle.height / 2f
        )
    }
}
