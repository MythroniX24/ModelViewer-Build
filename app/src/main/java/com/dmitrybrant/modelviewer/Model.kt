package com.dmitrybrant.modelviewer

import android.opengl.Matrix

abstract class Model {

    protected var centerMassX = 0f
    protected var centerMassY = 0f
    protected var centerMassZ = 0f

    var floorOffset = 0f
        protected set

    var title = ""
    protected var glProgram = -1

    var modelMatrix = FloatArray(16)
        protected set
    protected var mvMatrix = FloatArray(16)
    protected var mvpMatrix = FloatArray(16)

    protected var maxX = -Float.MAX_VALUE
    protected var maxY = -Float.MAX_VALUE
    protected var maxZ = -Float.MAX_VALUE
    protected var minX = Float.MAX_VALUE
    protected var minY = Float.MAX_VALUE
    protected var minZ = Float.MAX_VALUE

    // Custom per-axis scale
    var customScaleX = 1f
    var customScaleY = 1f
    var customScaleZ = 1f

    // Model info
    var originalTriangleCount = 0
    var displayTriangleCount = 0
    var fileSizeBytes = 0L
    var isDecimated = false

    // Connected parts (populated after loading if model has multiple components)
    var parts: List<ModelPart> = emptyList()
    var selectedPartIndex: Int = -1  // -1 = whole model selected

    private var savedBoundSize = 50f

    val originalSizeX: Float get() = if (maxX != -Float.MAX_VALUE && minX != Float.MAX_VALUE) maxX - minX else 0f
    val originalSizeY: Float get() = if (maxY != -Float.MAX_VALUE && minY != Float.MAX_VALUE) maxY - minY else 0f
    val originalSizeZ: Float get() = if (maxZ != -Float.MAX_VALUE && minZ != Float.MAX_VALUE) maxZ - minZ else 0f

    val currentSizeXmm: Float get() = originalSizeX * customScaleX
    val currentSizeYmm: Float get() = originalSizeY * customScaleY
    val currentSizeZmm: Float get() = originalSizeZ * customScaleZ

    open fun setup(boundSize: Float) {
        savedBoundSize = boundSize
        initModelMatrix(boundSize)
    }

    fun refreshModelMatrix() { initModelMatrix(savedBoundSize) }

    protected open fun initModelMatrix(boundSize: Float) {
        initModelMatrix(boundSize, 0.0f, 0.0f, 0.0f)
    }

    protected fun initModelMatrix(boundSize: Float, rotateX: Float, rotateY: Float, rotateZ: Float) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotateX, 1.0f, 0.0f, 0.0f)
        Matrix.rotateM(modelMatrix, 0, rotateY, 0.0f, 1.0f, 0.0f)
        Matrix.rotateM(modelMatrix, 0, rotateZ, 0.0f, 0.0f, 1.0f)
        scaleModelMatrixToBounds(boundSize)
        Matrix.scaleM(modelMatrix, 0, customScaleX, customScaleY, customScaleZ)
        Matrix.translateM(modelMatrix, 0, -centerMassX, -centerMassY, -centerMassZ)
    }

    abstract fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, light: Light)

    protected fun adjustMaxMin(x: Float, y: Float, z: Float) {
        if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
    }

    protected fun getBoundScale(boundSize: Float): Float {
        val scaleX = (maxX - minX) / boundSize
        val scaleY = (maxY - minY) / boundSize
        val scaleZ = (maxZ - minZ) / boundSize
        var scale = scaleX
        if (scaleY > scale) scale = scaleY
        if (scaleZ > scale) scale = scaleZ
        return scale
    }

    private fun scaleModelMatrixToBounds(boundSize: Float) {
        var scale = getBoundScale(boundSize)
        if (scale != 0f) { scale = 1f / scale; Matrix.scaleM(modelMatrix, 0, scale, scale, scale) }
    }
}
