package com.dmitrybrant.modelviewer

import android.content.ContentResolver
import android.net.Uri
import com.dmitrybrant.modelviewer.util.Util
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Exports model as binary STL with scale applied.
 * For large decimated models: re-reads original file triangle by triangle (full quality).
 * For normal models: uses in-memory vertex buffer.
 */
object StlExporter {

    /**
     * Export with progress callback (0-100).
     * If model is decimated and originalUri is provided, exports full quality from original file.
     */
    fun export(
        model: Model,
        outputStream: OutputStream,
        contentResolver: ContentResolver? = null,
        originalUri: Uri? = null,
        progressCallback: ((Int) -> Unit)? = null
    ) {
        if (model.isDecimated && contentResolver != null && originalUri != null) {
            exportFromOriginalFile(model, outputStream, contentResolver, originalUri, progressCallback)
        } else {
            exportFromMemory(model, outputStream, progressCallback)
        }
    }

    // ── Export from original file (full quality, for decimated large models) ──

    private fun exportFromOriginalFile(
        model: Model, outputStream: OutputStream,
        contentResolver: ContentResolver, uri: Uri,
        progressCallback: ((Int) -> Unit)?
    ) {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open original file.")
        val buffered = BufferedInputStream(stream, 0x10000)

        try {
            // Detect binary STL
            val testBytes = ByteArray(256)
            buffered.mark(256)
            buffered.read(testBytes, 0, 256)
            buffered.reset()
            val isText = String(testBytes).let {
                it.contains("solid") && it.contains("facet") && it.contains("vertex")
            }

            if (isText) {
                exportTextFromOriginal(model, buffered, outputStream, progressCallback)
            } else {
                exportBinaryFromOriginal(model, buffered, outputStream, progressCallback)
            }
        } finally {
            stream.close()
        }
    }

    private fun exportBinaryFromOriginal(
        model: Model, stream: BufferedInputStream,
        outputStream: OutputStream, progressCallback: ((Int) -> Unit)?
    ) {
        val sx = model.customScaleX; val sy = model.customScaleY; val sz = model.customScaleZ
        val tempBytes = ByteArray(50)

        // Skip 80-byte header
        stream.skip(80)
        stream.read(tempBytes, 0, 4)
        val totalTriangles = Util.readIntLe(tempBytes, 0)

        // Write output header
        val header = ByteArray(80)
        "Exported by 3D Model Viewer".toByteArray().copyInto(header, 0, 0, 27)
        outputStream.write(header)
        val countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        countBuf.putInt(totalTriangles); outputStream.write(countBuf.array())

        val triBuf = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        var lastProgress = -1

        for (i in 0 until totalTriangles) {
            stream.read(tempBytes, 0, 50)
            val progress = (i * 100L / totalTriangles).toInt()
            if (progress != lastProgress) { lastProgress = progress; progressCallback?.invoke(progress) }

            val nx = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 0))
            val ny = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 4))
            val nz = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 8))

            triBuf.clear()
            triBuf.putFloat(nx); triBuf.putFloat(ny); triBuf.putFloat(nz)
            for (v in 0..2) {
                val x = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 12 + v * 12)) * sx
                val y = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 16 + v * 12)) * sy
                val z = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 20 + v * 12)) * sz
                triBuf.putFloat(x); triBuf.putFloat(y); triBuf.putFloat(z)
            }
            triBuf.putShort(0)
            outputStream.write(triBuf.array())
        }
    }

    private fun exportTextFromOriginal(
        model: Model, stream: InputStream,
        outputStream: OutputStream, progressCallback: ((Int) -> Unit)?
    ) {
        // For text STL, collect all triangles then write binary
        val sx = model.customScaleX; val sy = model.customScaleY; val sz = model.customScaleZ
        val triangles = mutableListOf<FloatArray>()
        val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
        val pattern = java.util.regex.Pattern.compile("\\s+")
        var currentVertices = FloatArray(9)
        var currentNormal = FloatArray(3)
        var vertIdx = 0
        var line: String = ""

        while (reader.readLine().also { line = it.orEmpty() } != null) {
            line = line.trim()
            when {
                line.startsWith("facet normal") -> {
                    val arr = pattern.split(line.removePrefix("facet normal").trim(), 0)
                    if (arr.size >= 3) { currentNormal[0] = arr[0].toFloatOrNull() ?: 0f; currentNormal[1] = arr[1].toFloatOrNull() ?: 0f; currentNormal[2] = arr[2].toFloatOrNull() ?: 0f }
                    vertIdx = 0
                }
                line.startsWith("vertex") -> {
                    val arr = pattern.split(line.removePrefix("vertex").trim(), 0)
                    if (arr.size >= 3 && vertIdx < 3) {
                        val b = vertIdx * 3
                        currentVertices[b] = (arr[0].toFloatOrNull() ?: 0f) * sx
                        currentVertices[b+1] = (arr[1].toFloatOrNull() ?: 0f) * sy
                        currentVertices[b+2] = (arr[2].toFloatOrNull() ?: 0f) * sz
                        vertIdx++
                    }
                }
                line.startsWith("endfacet") -> {
                    triangles.add(floatArrayOf(
                        currentVertices[0], currentVertices[1], currentVertices[2],
                        currentVertices[3], currentVertices[4], currentVertices[5],
                        currentVertices[6], currentVertices[7], currentVertices[8],
                        currentNormal[0], currentNormal[1], currentNormal[2]
                    ))
                }
            }
        }

        writeTrianglesToBinary(triangles, outputStream, progressCallback)
    }

    // ── Export from in-memory buffer (normal sized models) ──

    private fun exportFromMemory(model: Model, outputStream: OutputStream, progressCallback: ((Int) -> Unit)?) {
        val triangles = buildTrianglesFromMemory(model)
            ?: throw IllegalStateException("Model has no vertex data to export.")
        writeTrianglesToBinary(triangles, outputStream, progressCallback)
    }

    private fun writeTrianglesToBinary(
        triangles: List<FloatArray>, outputStream: OutputStream,
        progressCallback: ((Int) -> Unit)?
    ) {
        val header = ByteArray(80)
        "Exported by 3D Model Viewer".toByteArray().copyInto(header, 0, 0, 27)
        outputStream.write(header)
        val countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        countBuf.putInt(triangles.size); outputStream.write(countBuf.array())

        val triBuf = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        var lastProgress = -1
        triangles.forEachIndexed { i, tri ->
            val progress = (i * 100L / triangles.size).toInt()
            if (progress != lastProgress) { lastProgress = progress; progressCallback?.invoke(progress) }
            triBuf.clear()
            triBuf.putFloat(tri[9]); triBuf.putFloat(tri[10]); triBuf.putFloat(tri[11])
            for (v in 0..2) { triBuf.putFloat(tri[v*3]); triBuf.putFloat(tri[v*3+1]); triBuf.putFloat(tri[v*3+2]) }
            triBuf.putShort(0); outputStream.write(triBuf.array())
        }
    }

    private fun buildTrianglesFromMemory(model: Model): List<FloatArray>? {
        val arrayModel = model as? ArrayModel ?: return null
        val vBuf = arrayModel.vertexBuffer ?: return null
        val nBuf = arrayModel.normalBuffer
        val sx = model.customScaleX; val sy = model.customScaleY; val sz = model.customScaleZ

        vBuf.position(0); val vertexFloats = FloatArray(vBuf.limit()).also { vBuf.get(it) }; vBuf.position(0)
        var normalFloats: FloatArray? = null
        if (nBuf != null) { nBuf.position(0); normalFloats = FloatArray(nBuf.limit()).also { nBuf.get(it) }; nBuf.position(0) }

        val indexedModel = model as? IndexedModel
        if (indexedModel != null) return buildFromIndexed(indexedModel, vertexFloats, normalFloats, sx, sy, sz)

        val triangleCount = vertexFloats.size / 9
        return (0 until triangleCount).map { i ->
            val tri = FloatArray(12); val base = i * 9
            tri[0] = vertexFloats[base]*sx;     tri[1] = vertexFloats[base+1]*sy; tri[2] = vertexFloats[base+2]*sz
            tri[3] = vertexFloats[base+3]*sx;   tri[4] = vertexFloats[base+4]*sy; tri[5] = vertexFloats[base+5]*sz
            tri[6] = vertexFloats[base+6]*sx;   tri[7] = vertexFloats[base+7]*sy; tri[8] = vertexFloats[base+8]*sz
            if (normalFloats != null && base+2 < normalFloats.size) { tri[9]=normalFloats[base]; tri[10]=normalFloats[base+1]; tri[11]=normalFloats[base+2] }
            else computeNormal(tri)
            tri
        }
    }

    private fun buildFromIndexed(model: IndexedModel, vertexFloats: FloatArray, normalFloats: FloatArray?, sx: Float, sy: Float, sz: Float): List<FloatArray> {
        val iBuf = model.indexBuffer ?: return emptyList()
        iBuf.position(0); val indices = IntArray(iBuf.limit()).also { iBuf.get(it) }; iBuf.position(0)
        return (0 until indices.size / 3).mapNotNull { i ->
            val i0=indices[i*3]*3; val i1=indices[i*3+1]*3; val i2=indices[i*3+2]*3
            if (i0+2>=vertexFloats.size||i1+2>=vertexFloats.size||i2+2>=vertexFloats.size) return@mapNotNull null
            val tri = FloatArray(12)
            tri[0]=vertexFloats[i0]*sx;   tri[1]=vertexFloats[i0+1]*sy; tri[2]=vertexFloats[i0+2]*sz
            tri[3]=vertexFloats[i1]*sx;   tri[4]=vertexFloats[i1+1]*sy; tri[5]=vertexFloats[i1+2]*sz
            tri[6]=vertexFloats[i2]*sx;   tri[7]=vertexFloats[i2+1]*sy; tri[8]=vertexFloats[i2+2]*sz
            if (normalFloats!=null&&i0+2<normalFloats.size) { tri[9]=normalFloats[i0]; tri[10]=normalFloats[i0+1]; tri[11]=normalFloats[i0+2] }
            else computeNormal(tri)
            tri
        }
    }

    private fun computeNormal(tri: FloatArray) {
        val ux=tri[3]-tri[0]; val uy=tri[4]-tri[1]; val uz=tri[5]-tri[2]
        val vx=tri[6]-tri[0]; val vy=tri[7]-tri[1]; val vz=tri[8]-tri[2]
        var nx=uy*vz-uz*vy; var ny=uz*vx-ux*vz; var nz=ux*vy-uy*vx
        val len=sqrt((nx*nx+ny*ny+nz*nz).toDouble()).toFloat()
        if (len>0f) { nx/=len; ny/=len; nz/=len }
        tri[9]=nx; tri[10]=ny; tri[11]=nz
    }
}
