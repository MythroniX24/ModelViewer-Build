package com.dmitrybrant.modelviewer

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer(val model: Model?) : GLSurfaceView.Renderer {
    private val light = Light(floatArrayOf(0.0f, 0.0f, MODEL_BOUND_SIZE * 10, 1.0f))
    private val floor = Floor()

    val projectionMatrix = FloatArray(16)
    val viewMatrix = FloatArray(16)

    private var rotateAngleX = -15.0f; private var rotateAngleY = 15.0f
    private var translateX = 0f; private var translateY = 0f
    private var translateZ = -MODEL_BOUND_SIZE * 1.5f

    private var bgR = 0.2f; private var bgG = 0.2f; private var bgB = 0.2f
    private var modelR = 0.7f; private var modelG = 0.7f; private var modelB = 0.85f

    private var screenshotCb: ((Bitmap) -> Unit)? = null
    private var surfaceW = 0; private var surfaceH = 0

    fun translate(dx: Float, dy: Float, dz: Float) {
        translateX += dx * (MODEL_BOUND_SIZE / 200f)
        translateY += dy * (MODEL_BOUND_SIZE / 200f)
        if (dz != 0f) translateZ /= dz
        updateViewMatrix()
    }

    fun rotate(aX: Float, aY: Float) {
        rotateAngleX -= aX * 0.5f; rotateAngleY += aY * 0.5f; updateViewMatrix()
    }

    fun resetView() {
        rotateAngleX = -15.0f; rotateAngleY = 15.0f
        translateX = 0f; translateY = 0f; translateZ = -MODEL_BOUND_SIZE * 1.5f
        updateViewMatrix()
    }

    fun applyModelScale(scaleX: Float, scaleY: Float, scaleZ: Float) {
        model?.let { it.customScaleX=scaleX; it.customScaleY=scaleY; it.customScaleZ=scaleZ; it.refreshModelMatrix() }
    }

    fun applyPartScale(partIndex: Int, scaleX: Float, scaleY: Float, scaleZ: Float) {
        val parts = model?.parts ?: return
        if (partIndex < 0 || partIndex >= parts.size) return
        val part = parts[partIndex]
        part.scaleX = scaleX; part.scaleY = scaleY; part.scaleZ = scaleZ
        model.refreshModelMatrix()
    }

    fun setBackgroundColor(r: Float, g: Float, b: Float) {
        bgR=r; bgG=g; bgB=b; GLES20.glClearColor(r,g,b,1f)
    }

    fun setModelColor(r: Float, g: Float, b: Float) { modelR=r; modelG=g; modelB=b }

    fun requestScreenshot(cb: (Bitmap) -> Unit) { screenshotCb = cb }

    private fun updateViewMatrix() {
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, translateZ, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
        Matrix.translateM(viewMatrix, 0, -translateX, -translateY, 0f)
        Matrix.rotateM(viewMatrix, 0, rotateAngleX, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, rotateAngleY, 0f, 1f, 0f)
    }

    override fun onDrawFrame(unused: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        // Always disable cull face - two-sided shader handles lighting correctly
        // This prevents holes in any model (decimated or not)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        floor.draw(viewMatrix, projectionMatrix, light)
        model?.draw(viewMatrix, projectionMatrix, light)

        screenshotCb?.let { cb ->
            screenshotCb = null
            val buf = ByteBuffer.allocateDirect(surfaceW * surfaceH * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, surfaceW, surfaceH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            val bmp = Bitmap.createBitmap(surfaceW, surfaceH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            val flipped = Bitmap.createBitmap(bmp, 0, 0, surfaceW, surfaceH,
                android.graphics.Matrix().apply { postScale(1f, -1f, surfaceW/2f, surfaceH/2f) }, true)
            bmp.recycle(); cb(flipped)
        }
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        surfaceW = width; surfaceH = height
        GLES20.glViewport(0, 0, width, height)
        Matrix.frustumM(projectionMatrix, 0, -width.toFloat()/height, width.toFloat()/height, -1f, 1f, Z_NEAR, Z_FAR)
        resetView(); light.applyViewMatrix(viewMatrix)
        rotateAngleX = -15.0f; rotateAngleY = 15.0f; updateViewMatrix()
    }

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        GLES20.glClearColor(bgR, bgG, bgB, 1f)
        GLES20.glDisable(GLES20.GL_CULL_FACE)  // Two-sided lighting always
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        floor.setup(MODEL_BOUND_SIZE)
        model?.let { it.setup(MODEL_BOUND_SIZE); floor.setOffsetY(it.floorOffset) }
    }

    companion object {
        private const val MODEL_BOUND_SIZE = 50f
        private const val Z_NEAR = 2f
        private const val Z_FAR = MODEL_BOUND_SIZE * 10
    }
}
