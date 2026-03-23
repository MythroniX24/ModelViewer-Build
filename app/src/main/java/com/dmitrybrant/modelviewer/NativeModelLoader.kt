package com.dmitrybrant.modelviewer

import java.nio.ByteBuffer
import java.nio.ByteOrder

object NativeModelLoader {

    init { System.loadLibrary("modelviewer") }

    // All C++ — load from file descriptor (zero Java heap)
    @JvmStatic external fun loadFromFd(fd: Int, offset: Long, length: Long,
        formatHint: String, progressCallback: ((Int) -> Unit)?): Long

    // Load from bytes (assets/HTTP)
    @JvmStatic external fun loadFromBytes(data: ByteArray,
        formatHint: String, progressCallback: ((Int) -> Unit)?): Long

    // Export — C++ returns byte array, Kotlin just writes to file
    @JvmStatic external fun exportModel(handle: Long, format: String,
        sx: Float, sy: Float, sz: Float): ByteArray?

    @JvmStatic external fun getVertexBuffer(handle: Long): ByteBuffer?
    @JvmStatic external fun getNormalBuffer(handle: Long): ByteBuffer?
    @JvmStatic external fun getModelInfo(handle: Long): FloatArray?
    @JvmStatic external fun freeModel(handle: Long)

    fun fillModel(handle: Long, model: ArrayModel, progress: ((Int) -> Unit)?): Boolean {
        if (handle == 0L) return false
        try {
            val info = getModelInfo(handle) ?: return false
            val vBuf = getVertexBuffer(handle) ?: return false
            val nBuf = getNormalBuffer(handle) ?: return false

            val vBB = ByteBuffer.allocateDirect(vBuf.capacity()).order(ByteOrder.nativeOrder())
            val nBB = ByteBuffer.allocateDirect(nBuf.capacity()).order(ByteOrder.nativeOrder())
            vBuf.order(ByteOrder.nativeOrder()); nBuf.order(ByteOrder.nativeOrder())
            vBB.put(vBuf); vBB.position(0)
            nBB.put(nBuf); nBB.position(0)

            model.vertexBuffer = vBB.asFloatBuffer()
            model.normalBuffer = nBB.asFloatBuffer()
            model.setMinMax(info[0],info[1],info[2],info[3],info[4],info[5])
            model.setCenterMass(info[6],info[7],info[8])
            model.vertexCount = info[9].toInt()
            model.originalTriangleCount = info[10].toInt()
            model.displayTriangleCount = info[11].toInt()
            model.isDecimated = info[12] != 0f
            progress?.invoke(100)
            return true
        } finally {
            freeModel(handle)
        }
    }
}
