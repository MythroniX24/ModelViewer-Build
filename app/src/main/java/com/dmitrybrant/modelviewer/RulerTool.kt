package com.dmitrybrant.modelviewer

import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.sqrt

class RulerTool {
    data class Point3D(val x: Float, val y: Float, val z: Float)

    var pointA: Point3D? = null
    var pointB: Point3D? = null
    // Screen coords for overlay drawing
    var pointAScreen: Pair<Float, Float>? = null
    var pointBScreen: Pair<Float, Float>? = null

    val distance: Float get() {
        val a = pointA ?: return 0f
        val b = pointB ?: return 0f
        val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
        return sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
    }

    fun reset() { pointA=null; pointB=null; pointAScreen=null; pointBScreen=null }

    /**
     * Pick a point. Returns true if hit.
     * Also stores screen coordinates for overlay.
     */
    fun pickPoint(
        touchX: Float, touchY: Float,
        screenX: Float, screenY: Float,
        viewMatrix: FloatArray, projectionMatrix: FloatArray,
        model: Model
    ): Boolean {
        val arrayModel = model as? ArrayModel ?: return false
        val vBuf = arrayModel.vertexBuffer ?: return false

        val ndcX = touchX; val ndcY = touchY
        val ray = unproject(ndcX, ndcY, viewMatrix, projectionMatrix, model.modelMatrix)
        val rayOrigin = ray.first; val rayDir = ray.second

        vBuf.position(0)
        val verts = FloatArray(vBuf.limit()).also { vBuf.get(it) }
        vBuf.position(0)

        var nearestT = Float.MAX_VALUE
        var hitX = 0f; var hitY = 0f; var hitZ = 0f
        val triCount = verts.size / 9

        for (t in 0 until triCount) {
            val base = t * 9
            val v0 = floatArrayOf(verts[base],   verts[base+1], verts[base+2])
            val v1 = floatArrayOf(verts[base+3], verts[base+4], verts[base+5])
            val v2 = floatArrayOf(verts[base+6], verts[base+7], verts[base+8])
            val hit = rayTriangle(rayOrigin, rayDir, v0, v1, v2)
            if (hit != null && hit < nearestT) {
                nearestT = hit
                hitX = rayOrigin[0] + rayDir[0] * hit
                hitY = rayOrigin[1] + rayDir[1] * hit
                hitZ = rayOrigin[2] + rayDir[2] * hit
            }
        }

        if (nearestT == Float.MAX_VALUE) return false

        // Convert back to model space
        val invModel = FloatArray(16)
        Matrix.invertM(invModel, 0, model.modelMatrix, 0)
        val worldPt = floatArrayOf(hitX, hitY, hitZ, 1f)
        val modelPt = FloatArray(4)
        Matrix.multiplyMV(modelPt, 0, invModel, 0, worldPt, 0)

        val point = Point3D(modelPt[0], modelPt[1], modelPt[2])
        if (pointA == null) {
            pointA = point
            pointAScreen = Pair(screenX, screenY)
        } else {
            pointB = point
            pointBScreen = Pair(screenX, screenY)
        }
        return true
    }

    private fun unproject(
        touchX: Float, touchY: Float,
        viewMatrix: FloatArray, projectionMatrix: FloatArray,
        modelMatrix: FloatArray
    ): Pair<FloatArray, FloatArray> {
        val mvMatrix = FloatArray(16); val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)
        val invMvp = FloatArray(16)
        Matrix.invertM(invMvp, 0, mvpMatrix, 0)

        val nearNdc = floatArrayOf(touchX, touchY, -1f, 1f)
        val farNdc  = floatArrayOf(touchX, touchY,  1f, 1f)
        val nearWorld = FloatArray(4); val farWorld = FloatArray(4)
        Matrix.multiplyMV(nearWorld, 0, invMvp, 0, nearNdc, 0)
        Matrix.multiplyMV(farWorld,  0, invMvp, 0, farNdc,  0)

        if (nearWorld[3] != 0f) { nearWorld[0]/=nearWorld[3]; nearWorld[1]/=nearWorld[3]; nearWorld[2]/=nearWorld[3] }
        if (farWorld[3]  != 0f) { farWorld[0]/=farWorld[3];   farWorld[1]/=farWorld[3];   farWorld[2]/=farWorld[3] }

        val dir = floatArrayOf(farWorld[0]-nearWorld[0], farWorld[1]-nearWorld[1], farWorld[2]-nearWorld[2])
        val len = sqrt((dir[0]*dir[0]+dir[1]*dir[1]+dir[2]*dir[2]).toDouble()).toFloat()
        if (len > 0f) { dir[0]/=len; dir[1]/=len; dir[2]/=len }

        return Pair(floatArrayOf(nearWorld[0], nearWorld[1], nearWorld[2]), dir)
    }

    private fun rayTriangle(orig: FloatArray, dir: FloatArray, v0: FloatArray, v1: FloatArray, v2: FloatArray): Float? {
        val EPSILON = 1e-7f
        val e1 = fa(v1[0]-v0[0], v1[1]-v0[1], v1[2]-v0[2])
        val e2 = fa(v2[0]-v0[0], v2[1]-v0[1], v2[2]-v0[2])
        val h = cross(dir, e2)
        val a = dot(e1, h)
        if (abs(a) < EPSILON) return null
        val f = 1f / a
        val s = fa(orig[0]-v0[0], orig[1]-v0[1], orig[2]-v0[2])
        val u = f * dot(s, h)
        if (u < 0f || u > 1f) return null
        val q = cross(s, e1)
        val v = f * dot(dir, q)
        if (v < 0f || u + v > 1f) return null
        val t = f * dot(e2, q)
        return if (t > EPSILON) t else null
    }

    private fun fa(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    private fun dot(a: FloatArray, b: FloatArray) = a[0]*b[0] + a[1]*b[1] + a[2]*b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
}
