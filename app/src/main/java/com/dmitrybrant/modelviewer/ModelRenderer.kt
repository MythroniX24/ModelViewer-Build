package com.dmitrybrant.modelviewer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer(val model: Model?) : GLSurfaceView.Renderer {
    private val light = Light(floatArrayOf(0.0f, 0.0f, MODEL_BOUND_SIZE * 10, 1.0f))
    private val floor = Floor()

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private var rotateAngleX = 0f
    private var rotateAngleY = 0f
    private var translateX = 0f
    private var translateY = 0f
    private var translateZ = 0f

    fun translate(dx: Float, dy: Float, dz: Float) {
        val translateScaleFactor = MODEL_BOUND_SIZE / 200f
        translateX += dx * translateScaleFactor
        translateY += dy * translateScaleFactor
        if (dz != 0f) {
            translateZ /= dz
        }
        updateViewMatrix()
    }

    fun rotate(aX: Float, aY: Float) {
        val rotateScaleFactor = 0.5f
        rotateAngleX -= aX * rotateScaleFactor
        rotateAngleY += aY * rotateScaleFactor
        updateViewMatrix()
    }

    /**
     * Apply per-axis scale (as ratios relative to original size).
     * Must be called from the GL thread (use queueEvent from SurfaceView).
     */
    fun applyModelScale(scaleX: Float, scaleY: Float, scaleZ: Float) {
        model?.let {
            it.customScaleX = scaleX
            it.customScaleY = scaleY
            it.customScaleZ = scaleZ
            it.refreshModelMatrix()
        }
    }

    private fun updateViewMatrix() {
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, translateZ, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
        Matrix.translateM(viewMatrix, 0, -translateX, -translateY, 0f)
        Matrix.rotateM(viewMatrix, 0, rotateAngleX, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, rotateAngleY, 0f, 1f, 0f)
    }

    override fun onDrawFrame(unused: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        floor.draw(viewMatrix, projectionMatrix, light)
        model?.draw(viewMatrix, projectionMatrix, light)
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, Z_NEAR, Z_FAR)

        rotateAngleX = 0f
        rotateAngleY = 0f
        translateX = 0f
        translateY = 0f
        translateZ = -MODEL_BOUND_SIZE * 1.5f
        updateViewMatrix()

        light.applyViewMatrix(viewMatrix)

        rotateAngleX = -15.0f
        rotateAngleY = 15.0f
        updateViewMatrix()
    }

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1f)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        floor.setup(MODEL_BOUND_SIZE)
        model?.let {
            it.setup(MODEL_BOUND_SIZE)
            floor.setOffsetY(it.floorOffset)
        }
    }

    companion object {
        private const val MODEL_BOUND_SIZE = 50f
        private const val Z_NEAR = 2f
        private const val Z_FAR = MODEL_BOUND_SIZE * 10
    }
}
