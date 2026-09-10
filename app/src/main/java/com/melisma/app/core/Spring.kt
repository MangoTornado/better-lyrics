package com.melisma.app.core

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analytic damped-harmonic spring. Port of Fraktality's `spr` (MIT), which is what
 * Spicy Lyrics uses to drive every per-word scale / lift / glow value.
 *
 * Analytic rather than numeric, so it is exact for any timestep — a dropped frame
 * doesn't change where the value lands, it just gets there in one bigger step.
 */
class Spring(
    startPosition: Float,
    private var frequency: Float,
    private var dampingRatio: Float,
    goal: Float = startPosition,
) {
    private var goalValue: Float = goal
    private var position: Float = startPosition
    private var velocity: Float = 0f

    val value: Float get() = position

    fun step(dt: Float): Float {
        if (dt <= 0f) return position

        val d = dampingRatio
        val f = frequency * TWO_PI
        val g = goalValue
        var p = position
        var v = velocity

        when {
            d == 1f -> { // critically damped
                val q = exp(-f * dt)
                val w = dt * q

                val c0 = q + w * f
                val c2 = q - w * f
                val c3 = w * f * f

                val o = p - g
                p = o * c0 + v * w + g
                v = v * c2 - o * c3
            }

            d < 1f -> { // underdamped
                val q = exp(-d * f * dt)
                val c = sqrt(1f - d * d)

                val i = cos(dt * f * c)
                val j = sin(dt * f * c)

                val z = if (c > EPS) {
                    j / c
                } else {
                    val a = dt * f
                    a + ((a * a) * (c * c) * (c * c) / 20f - c * c) * (a * a * a) / 6f
                }

                val y = if (f * c > EPS) {
                    j / (f * c)
                } else {
                    val b = f * c
                    dt + ((dt * dt) * (b * b) * (b * b) / 20f - b * b) * (dt * dt * dt) / 6f
                }

                val o = p - g
                p = (o * (i + z * d) + v * y) * q + g
                v = (v * (i - z * d) - o * (z * f)) * q
            }

            else -> { // overdamped
                val c = sqrt(d * d - 1f)

                val r1 = -f * (d + c)
                val r2 = -f * (d - c)

                val ec1 = exp(r1 * dt)
                val ec2 = exp(r2 * dt)

                val o = p - g
                val co2 = (v - o * r1) / (2f * f * c)
                val co1 = ec1 * (o - co2)

                p = co1 + co2 * ec2 + g
                v = co1 * r1 + co2 * ec2 * r2
            }
        }

        position = p
        velocity = v
        return p
    }

    fun canSleep(): Boolean {
        if (velocity * velocity > SLEEP_VELOCITY_SQ_LIMIT) return false
        val offset = position - goalValue
        return offset * offset <= SLEEP_OFFSET_SQ_LIMIT
    }

    fun setGoal(goal: Float, snap: Boolean = false) {
        goalValue = goal
        if (snap) {
            position = goal
            velocity = 0f
        }
    }

    fun snapTo(value: Float) {
        goalValue = value
        position = value
        velocity = 0f
    }

    fun setFrequency(hz: Float) {
        frequency = hz
    }

    fun setDampingRatio(ratio: Float) {
        dampingRatio = ratio
    }

    private companion object {
        const val TWO_PI = (2.0 * Math.PI).toFloat()
        const val EPS = 1e-5f
        val SLEEP_OFFSET_SQ_LIMIT = (1f / 3840f) * (1f / 3840f)
        const val SLEEP_VELOCITY_SQ_LIMIT = 1e-2f * 1e-2f
    }
}
