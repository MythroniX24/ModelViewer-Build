package com.dmitrybrant.modelviewer

import java.io.OutputStream
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Exports model as STL (binary), OBJ (text), or PLY (binary).
 * Scale is applied to all vertices.
 */
object ModelExporter {

    enum class Format { STL, OBJ, PLY }

    fun export(
        model: Model,
        outputStream: OutputStream,
        format: Format,
        progressCallback: ((Int) -> Unit)? = null
    ) {
        when (format) {
            Format.STL -> StlExporter.export(model, outputStream, null, null, progressCallback)
            Format.OBJ -> exportObj(model, outputStream, progressCallback)
            Format.PLY -> exportPly(model, outputStream, progressCallback)
        }
    }

    private fun exportObj(model: Model, outputStream: OutputStream, progress: ((Int) -> Unit)?) {
        val arrayModel = model as? ArrayModel ?: throw IllegalStateException("Not an ArrayModel")
        val vBuf = arrayModel.vertexBuffer ?: throw IllegalStateException("No vertex data")
        val nBuf = arrayModel.normalBuffer

        val sx = model.customScaleX; val sy = model.customScaleY; val sz = model.customScaleZ

        vBuf.position(0)
        val verts = FloatArray(vBuf.limit()).also { vBuf.get(it) }
        vBuf.position(0)

        var norms: FloatArray? = null
        if (nBuf != null) {
            nBuf.position(0); norms = FloatArray(nBuf.limit()).also { nBuf.get(it) }; nBuf.position(0)
        }

        val writer = PrintWriter(outputStream)
        writer.println("# Exported by 3D Model Viewer")
        writer.println("# Scale: X=${sx} Y=${sy} Z=${sz}")

        val vertCount = verts.size / 3
        val triCount = vertCount / 3
        var lastProgress = -1

        // Write vertices
        for (v in 0 until vertCount) {
            val b = v * 3
            writer.println("v ${verts[b]*sx} ${verts[b+1]*sy} ${verts[b+2]*sz}")
        }

        // Write normals
        if (norms != null) {
            for (v in 0 until vertCount) {
                val b = v * 3
                if (b + 2 < norms.size) writer.println("vn ${norms[b]} ${norms[b+1]} ${norms[b+2]}")
            }
        }

        // Write faces
        val hasNormals = norms != null
        for (t in 0 until triCount) {
            val p = (t * 100L / triCount).toInt()
            if (p != lastProgress) { lastProgress = p; progress?.invoke(p) }
            val i0 = t * 3 + 1; val i1 = t * 3 + 2; val i2 = t * 3 + 3
            if (hasNormals) {
                writer.println("f $i0//$i0 $i1//$i1 $i2//$i2")
            } else {
                writer.println("f $i0 $i1 $i2")
            }
        }
        writer.flush()
    }

    private fun exportPly(model: Model, outputStream: OutputStream, progress: ((Int) -> Unit)?) {
        val arrayModel = model as? ArrayModel ?: throw IllegalStateException("Not an ArrayModel")
        val vBuf = arrayModel.vertexBuffer ?: throw IllegalStateException("No vertex data")
        val nBuf = arrayModel.normalBuffer

        val sx = model.customScaleX; val sy = model.customScaleY; val sz = model.customScaleZ

        vBuf.position(0)
        val verts = FloatArray(vBuf.limit()).also { vBuf.get(it) }
        vBuf.position(0)

        var norms: FloatArray? = null
        if (nBuf != null) {
            nBuf.position(0); norms = FloatArray(nBuf.limit()).also { nBuf.get(it) }; nBuf.position(0)
        }

        val vertCount = verts.size / 3
        val triCount = vertCount / 3
        val hasNormals = norms != null && norms.size >= verts.size

        // PLY header
        val header = StringBuilder()
        header.appendLine("ply")
        header.appendLine("format binary_little_endian 1.0")
        header.appendLine("comment Exported by 3D Model Viewer")
        header.appendLine("element vertex $vertCount")
        header.appendLine("property float x")
        header.appendLine("property float y")
        header.appendLine("property float z")
        if (hasNormals) {
            header.appendLine("property float nx")
            header.appendLine("property float ny")
            header.appendLine("property float nz")
        }
        header.appendLine("element face $triCount")
        header.appendLine("property list uchar int vertex_indices")
        header.append("end_header\n")

        outputStream.write(header.toString().toByteArray(Charsets.US_ASCII))

        // Vertex data
        val floatsPerVertex = if (hasNormals) 6 else 3
        val buf = ByteBuffer.allocate(floatsPerVertex * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in 0 until vertCount) {
            val b = v * 3
            buf.clear()
            buf.putFloat(verts[b] * sx); buf.putFloat(verts[b+1] * sy); buf.putFloat(verts[b+2] * sz)
            if (hasNormals) {
                buf.putFloat(norms!![b]); buf.putFloat(norms[b+1]); buf.putFloat(norms[b+2])
            }
            outputStream.write(buf.array(), 0, floatsPerVertex * 4)
        }

        // Face data
        val faceBuf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN) // 1 + 3*4
        var lastProgress = -1
        for (t in 0 until triCount) {
            val p = (t * 100L / triCount).toInt()
            if (p != lastProgress) { lastProgress = p; progress?.invoke(p) }
            faceBuf.clear()
            faceBuf.put(3.toByte())
            faceBuf.putInt(t * 3); faceBuf.putInt(t * 3 + 1); faceBuf.putInt(t * 3 + 2)
            outputStream.write(faceBuf.array())
        }
    }

    fun fileExtension(format: Format) = when (format) {
        Format.STL -> "stl"
        Format.OBJ -> "obj"
        Format.PLY -> "ply"
    }

    fun mimeType(format: Format) = when (format) {
        Format.STL -> "application/sla"
        Format.OBJ -> "text/plain"
        Format.PLY -> "application/octet-stream"
    }
}
