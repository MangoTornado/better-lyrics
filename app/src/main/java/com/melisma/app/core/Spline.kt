package com.melisma.app.core

/**
 * Natural cubic spline through a small set of control points.
 *
 * Port of the `cubic-spline` npm package Spicy Lyrics uses to shape its animation
 * curves, so the ranges lifted from it (scale peaking at 1.05 at 70% through a
 * word, the lift that overshoots to -1/60 em, the glow that fades back to 0) bend
 * exactly the same way here.
 */
class Spline(private val xs: FloatArray, private val ys: FloatArray) {

    constructor(points: List<Pair<Float, Float>>) : this(
        FloatArray(points.size) { points[it].first },
        FloatArray(points.size) { points[it].second },
    )

    private val ks: FloatArray = naturalKs()

    private fun naturalKs(): FloatArray {
        val n = xs.size - 1
        // Augmented matrix: (n+1) rows, (n+2) columns.
        val a = Array(n + 1) { FloatArray(n + 2) }

        for (i in 1 until n) {
            val dxPrev = xs[i] - xs[i - 1]
            val dxNext = xs[i + 1] - xs[i]
            a[i][i - 1] = 1f / dxPrev
            a[i][i] = 2f * (1f / dxPrev + 1f / dxNext)
            a[i][i + 1] = 1f / dxNext
            a[i][n + 1] = 3f * (
                (ys[i] - ys[i - 1]) / (dxPrev * dxPrev) +
                    (ys[i + 1] - ys[i]) / (dxNext * dxNext)
                )
        }

        val dx0 = xs[1] - xs[0]
        a[0][0] = 2f / dx0
        a[0][1] = 1f / dx0
        a[0][n + 1] = 3f * (ys[1] - ys[0]) / (dx0 * dx0)

        val dxN = xs[n] - xs[n - 1]
        a[n][n - 1] = 1f / dxN
        a[n][n] = 2f / dxN
        a[n][n + 1] = 3f * (ys[n] - ys[n - 1]) / (dxN * dxN)

        return gaussianSolve(a)
    }

    private fun gaussianSolve(m: Array<FloatArray>): FloatArray {
        val rows = m.size
        val cols = m[0].size

        for (k in 0 until rows) {
            // Partial pivot.
            var maxRow = k
            for (i in k + 1 until rows) {
                if (kotlin.math.abs(m[i][k]) > kotlin.math.abs(m[maxRow][k])) maxRow = i
            }
            val tmp = m[k]; m[k] = m[maxRow]; m[maxRow] = tmp

            for (i in k + 1 until rows) {
                val factor = m[i][k] / m[k][k]
                for (j in k until cols) {
                    m[i][j] -= m[k][j] * factor
                }
            }
        }

        val out = FloatArray(rows)
        for (i in rows - 1 downTo 0) {
            var sum = 0f
            for (j in i + 1 until rows) sum += m[i][j] * out[j]
            out[i] = (m[i][cols - 1] - sum) / m[i][i]
        }
        return out
    }

    private fun indexBefore(target: Float): Int {
        var low = 0
        var high = xs.size
        var mid = 0
        while (low < high) {
            mid = (low + high) / 2
            if (xs[mid] < target) low = mid + 1 else high = mid
        }
        return if (low == 0) 1 else low
    }

    /** Value of the spline at [x]; ends are clamped rather than extrapolated. */
    fun at(x: Float): Float {
        if (xs.size == 1) return ys[0]
        val clamped = x.coerceIn(xs.first(), xs.last())
        val i = indexBefore(clamped)
        val dx = xs[i] - xs[i - 1]
        val t = (clamped - xs[i - 1]) / dx
        val dy = ys[i] - ys[i - 1]
        val a = ks[i - 1] * dx - dy
        val b = -ks[i] * dx + dy
        return (1f - t) * ys[i - 1] + t * ys[i] + t * (1f - t) * (a * (1f - t) + b * t)
    }
}
