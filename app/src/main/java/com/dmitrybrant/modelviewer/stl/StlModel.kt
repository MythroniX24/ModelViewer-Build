package com.dmitrybrant.modelviewer.stl

import com.dmitrybrant.modelviewer.ArrayModel
import com.dmitrybrant.modelviewer.util.Util
import com.dmitrybrant.modelviewer.util.Util.calculateNormal
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.regex.Pattern

class StlModel : ArrayModel {

    companion object {
        const val LARGE_FILE_THRESHOLD = 100L * 1024L * 1024L
        private const val HEADER_SIZE = 80
        private const val ASCII_TEST_SIZE = 256

        // Max triangles for DECIMATED / PREVIEW mode
        // 100K * 9 * 4 * 2 = 7.2MB — safe on any phone
        private const val MAX_PREVIEW_TRIANGLES = 100_000

        // Max triangles for FULL quality mode
        // 500K * 9 * 4 * 2 = 36MB — safe on phones with 2GB+ RAM
        private const val MAX_FULL_TRIANGLES = 500_000
    }

    private var cb: ((Int) -> Unit)? = null

    constructor(inputStream: InputStream) : super() {
        doLoad(inputStream, 0L, false, null)
    }

    constructor(
        inputStream: InputStream,
        fileSizeBytes: Long,
        wantDecimate: Boolean,
        progressCb: ((Int) -> Unit)?
    ) : super() {
        doLoad(inputStream, fileSizeBytes, wantDecimate, progressCb)
    }

    private fun doLoad(
        inputStream: InputStream,
        fileSize: Long,
        wantDecimate: Boolean,
        progressCb: ((Int) -> Unit)?
    ) {
        this.fileSizeBytes = fileSize
        this.cb = progressCb
        val stream = BufferedInputStream(inputStream, INPUT_BUFFER_SIZE)
        stream.mark(ASCII_TEST_SIZE)
        val isText = isTextFormat(stream)
        stream.reset()
        if (isText) readText(stream, wantDecimate)
        else readBinary(stream, wantDecimate)
        if (vertexCount <= 0 || vertexBuffer == null || normalBuffer == null)
            throw IOException("Invalid model.")
    }

    public override fun initModelMatrix(boundSize: Float) {
        initModelMatrix(boundSize, -90.0f, 0.0f, 180f)
        var scale = getBoundScale(boundSize)
        if (scale == 0.0f) scale = 1.0f
        floorOffset = (minZ - centerMassZ) / scale
    }

    private fun isTextFormat(stream: InputStream): Boolean {
        val b = ByteArray(ASCII_TEST_SIZE)
        val n = stream.read(b, 0, b.size)
        val s = String(b, 0, n)
        return s.contains("solid") && s.contains("facet") && s.contains("vertex")
    }

    private fun readText(stream: InputStream, wantDecimate: Boolean) {
        val maxTri = if (wantDecimate) MAX_PREVIEW_TRIANGLES else MAX_FULL_TRIANGLES
        val maxV = maxTri * 9
        val tmpVerts = FloatArray(maxV)
        val tmpNorms = FloatArray(maxV)
        var vPtr = 0; var nPtr = 0

        val reader = BufferedReader(InputStreamReader(stream), INPUT_BUFFER_SIZE)
        val pat = Pattern.compile("\\s+")
        var totalTri = 0
        val cn = FloatArray(3); val cv = FloatArray(9)
        var vi = 0; var hasNorm = false
        var line = ""

        while (reader.readLine().also { if (it != null) line = it } != null) {
            line = line.trim()
            when {
                line.startsWith("facet") -> {
                    val ln = line.removePrefix("facet normal").trim()
                    val a = pat.split(ln, 0)
                    if (a.size >= 3) {
                        cn[0]=a[0].toFloatOrNull()?:0f
                        cn[1]=a[1].toFloatOrNull()?:0f
                        cn[2]=a[2].toFloatOrNull()?:0f
                        hasNorm = true
                    }
                    vi = 0
                }
                line.startsWith("vertex") -> {
                    val ln = line.removePrefix("vertex").trim()
                    val a = pat.split(ln, 0)
                    if (a.size >= 3 && vi < 3) {
                        val b = vi*3
                        cv[b]=a[0].toFloatOrNull()?:0f
                        cv[b+1]=a[1].toFloatOrNull()?:0f
                        cv[b+2]=a[2].toFloatOrNull()?:0f
                        vi++
                    }
                }
                line.startsWith("endfacet") -> {
                    // Skip if over limit OR if decimating and this is an odd triangle
                    val keep = vPtr/9 < maxTri && (!wantDecimate || totalTri % 2 == 0)
                    if (keep) {
                        for (v in 0..2) {
                            val b = v*3
                            adjustMaxMin(cv[b], cv[b+1], cv[b+2])
                            tmpVerts[vPtr++]=cv[b]; tmpVerts[vPtr++]=cv[b+1]; tmpVerts[vPtr++]=cv[b+2]
                            tmpNorms[nPtr++]=cn[0]; tmpNorms[nPtr++]=cn[1]; tmpNorms[nPtr++]=cn[2]
                        }
                    }
                    totalTri++
                }
            }
        }

        originalTriangleCount = totalTri
        displayTriangleCount = vPtr / 9
        isDecimated = wantDecimate || totalTri > maxTri
        vertexCount = vPtr / 3
        if (vertexCount == 0) return

        var sumX = 0.0; var sumY = 0.0; var sumZ = 0.0
        var i = 0
        while (i < vPtr) { sumX += tmpVerts[i++]; sumY += tmpVerts[i++]; sumZ += tmpVerts[i++] }
        centerMassX = (sumX / vertexCount).toFloat()
        centerMassY = (sumY / vertexCount).toFloat()
        centerMassZ = (sumZ / vertexCount).toFloat()
        writeBuffers(tmpVerts, vPtr, tmpNorms, nPtr)
    }

    private fun readBinary(stream: BufferedInputStream, wantDecimate: Boolean) {
        val tmp = ByteArray(50)
        stream.skip(HEADER_SIZE.toLong())
        stream.read(tmp, 0, 4)

        val total = Util.readIntLe(tmp, 0)
        if (total < 0 || total > 50_000_000) throw IOException("Invalid model.")
        originalTriangleCount = total

        // Determine how many triangles to keep:
        // - 50% mode: cap at MAX_PREVIEW_TRIANGLES
        // - Full mode: cap at MAX_FULL_TRIANGLES (protects against OOM on huge files)
        val maxTri = if (wantDecimate) MAX_PREVIEW_TRIANGLES else MAX_FULL_TRIANGLES
        val keep = if (total > maxTri) maxTri else total
        isDecimated = keep < total
        displayTriangleCount = keep

        val verts = FloatArray(keep * 9)
        val norms = FloatArray(keep * 9)
        var vPtr = 0; var nPtr = 0
        var sumX = 0.0; var sumY = 0.0; var sumZ = 0.0
        var haveNorms = false; var lastProg = -1; var kept = 0
        val step = if (keep < total) total.toDouble() / keep.toDouble() else 1.0

        for (i in 0 until total) {
            stream.read(tmp, 0, 50)
            val prog = (i * 80L / total).toInt()
            if (prog != lastProg) { lastProg = prog; cb?.invoke(prog) }

            if (kept >= keep) continue
            val expectedKept = (i / step).toInt()
            if (expectedKept < kept) continue

            val nx = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 0))
            val ny = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 4))
            val nz = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 8))
            if (!haveNorms && (nx != 0f || ny != 0f || nz != 0f)) haveNorms = true
            norms[nPtr++]=nx; norms[nPtr++]=ny; norms[nPtr++]=nz
            norms[nPtr++]=nx; norms[nPtr++]=ny; norms[nPtr++]=nz
            norms[nPtr++]=nx; norms[nPtr++]=ny; norms[nPtr++]=nz

            var x = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 12))
            var y = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 16))
            var z = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 20))
            adjustMaxMin(x,y,z); sumX+=x; sumY+=y; sumZ+=z
            verts[vPtr++]=x; verts[vPtr++]=y; verts[vPtr++]=z

            x = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 24))
            y = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 28))
            z = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 32))
            adjustMaxMin(x,y,z); sumX+=x; sumY+=y; sumZ+=z
            verts[vPtr++]=x; verts[vPtr++]=y; verts[vPtr++]=z

            x = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 36))
            y = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 40))
            z = java.lang.Float.intBitsToFloat(Util.readIntLe(tmp, 44))
            adjustMaxMin(x,y,z); sumX+=x; sumY+=y; sumZ+=z
            verts[vPtr++]=x; verts[vPtr++]=y; verts[vPtr++]=z
            kept++
        }

        cb?.invoke(90)
        val vc = vPtr / 3
        if (vc == 0) return
        centerMassX = (sumX / vc).toFloat()
        centerMassY = (sumY / vc).toFloat()
        centerMassZ = (sumZ / vc).toFloat()
        vertexCount = vc

        if (!haveNorms) {
            val n = FloatArray(3); var idx = 0
            while (idx + 2 < vc) {
                calculateNormal(
                    verts[idx*3],verts[idx*3+1],verts[idx*3+2],
                    verts[(idx+1)*3],verts[(idx+1)*3+1],verts[(idx+1)*3+2],
                    verts[(idx+2)*3],verts[(idx+2)*3+1],verts[(idx+2)*3+2], n)
                for (v in 0..2) {
                    norms[(idx+v)*3]=n[0]; norms[(idx+v)*3+1]=n[1]; norms[(idx+v)*3+2]=n[2]
                }
                idx += 3
            }
        }
        cb?.invoke(95)
        writeBuffers(verts, vPtr, norms, nPtr)
    }

    private fun writeBuffers(verts: FloatArray, vLen: Int, norms: FloatArray, nLen: Int) {
        vertexCount = vLen / 3
        val vbb = ByteBuffer.allocateDirect(vLen * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder())
        vertexBuffer = vbb.asFloatBuffer().also { it.put(verts, 0, vLen); it.position(0) }
        val nbb = ByteBuffer.allocateDirect(nLen * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder())
        normalBuffer = nbb.asFloatBuffer().also { it.put(norms, 0, nLen); it.position(0) }
    }
}
