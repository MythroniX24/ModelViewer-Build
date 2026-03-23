package com.dmitrybrant.modelviewer

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.OutputStream

object ModelExporter {
    enum class Format { STL, OBJ, PLY }

    fun export(model: Model, out: OutputStream, formatStr: String,
               cr: ContentResolver? = null, originalUri: Uri? = null,
               progress: ((Int) -> Unit)? = null) {
        val am = model as? ArrayModel ?: throw IOException("Not supported")
        val sx = model.customScaleX; val sy = model.customScaleY; val sz = model.customScaleZ

        // If decimated and original available, reload full quality via C++
        if (model.isDecimated && cr != null && originalUri != null) {
            val pfd = cr.openFileDescriptor(originalUri, "r")
                ?: throw IOException("Cannot open original file")
            pfd.use {
                val fileSize = it.statSize
                val h = NativeModelLoader.loadFromFd(it.fd, 0L, fileSize, ".$formatStr", null)
                if (h == 0L) throw IOException("Cannot reload original")
                try {
                    val bytes = NativeModelLoader.exportModel(h, formatStr, sx, sy, sz)
                        ?: throw IOException("Export failed")
                    out.write(bytes)
                    progress?.invoke(100)
                } finally {
                    NativeModelLoader.freeModel(h)
                }
            }
        } else {
            // Export from current in-memory model
            // We need to pass the native handle — but VBO already uploaded
            // Use vertex buffer directly for export
            val vBuf = am.vertexBuffer ?: throw IOException("No vertex data")
            val nBuf = am.normalBuffer

            vBuf.position(0)
            val verts = FloatArray(vBuf.limit()).also { vBuf.get(it); vBuf.position(0) }
            val norms = if (nBuf != null) {
                nBuf.position(0); FloatArray(nBuf.limit()).also { nBuf.get(it); nBuf.position(0) }
            } else null

            // Write format
            when (formatStr.lowercase()) {
                "stl" -> writeStlBinary(verts, norms, sx, sy, sz, out, progress)
                "obj" -> writeObj(verts, norms, sx, sy, sz, out, progress)
                "ply" -> writePly(verts, norms, sx, sy, sz, out, progress)
                else -> writeStlBinary(verts, norms, sx, sy, sz, out, progress)
            }
        }
    }

    fun export(model: Model, out: OutputStream, format: Format,
               progress: ((Int) -> Unit)? = null) {
        export(model, out, format.name.lowercase(), null, null, progress)
    }

    fun fileExtension(f: Format) = f.name.lowercase()
    fun mimeType(f: Format) = when(f) {
        Format.STL -> "application/sla"
        Format.OBJ -> "text/plain"
        Format.PLY -> "application/octet-stream"
    }

    private fun writeStlBinary(verts: FloatArray, norms: FloatArray?,
                                sx: Float, sy: Float, sz: Float,
                                out: OutputStream, progress: ((Int)->Unit)?) {
        val tc = verts.size / 9
        val header = ByteArray(80).also {
            "Binary STL - 3D Model Viewer".toByteArray().copyInto(it)
        }
        out.write(header)
        val b4 = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b4.putInt(tc); out.write(b4.array())
        val tri = java.nio.ByteBuffer.allocate(50).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (t in 0 until tc) {
            if (t % 10000 == 0) progress?.invoke(t * 100 / tc)
            tri.clear(); val b = t*9
            val nx = norms?.get(b) ?: 0f; val ny = norms?.get(b+1) ?: 1f; val nz = norms?.get(b+2) ?: 0f
            tri.putFloat(nx); tri.putFloat(ny); tri.putFloat(nz)
            for (v in 0..2) { tri.putFloat(verts[b+v*3]*sx); tri.putFloat(verts[b+v*3+1]*sy); tri.putFloat(verts[b+v*3+2]*sz) }
            tri.putShort(0); out.write(tri.array())
        }
    }

    private fun writeObj(verts: FloatArray, norms: FloatArray?,
                          sx: Float, sy: Float, sz: Float,
                          out: OutputStream, progress: ((Int)->Unit)?) {
        val vc = verts.size / 3; val tc = vc / 3
        val sb = StringBuilder()
        sb.append("# 3D Model Viewer\n")
        for (i in 0 until vc) {
            val b = i*3
            sb.append("v ${verts[b]*sx} ${verts[b+1]*sy} ${verts[b+2]*sz}\n")
        }
        if (norms != null) {
            for (i in 0 until vc) { val b=i*3; sb.append("vn ${norms[b]} ${norms[b+1]} ${norms[b+2]}\n") }
            for (t in 0 until tc) { val b=t*3+1; sb.append("f $b//$b ${b+1}//${b+1} ${b+2}//${b+2}\n") }
        } else {
            for (t in 0 until tc) { val b=t*3+1; sb.append("f $b ${b+1} ${b+2}\n") }
        }
        out.write(sb.toString().toByteArray())
        progress?.invoke(100)
    }

    private fun writePly(verts: FloatArray, norms: FloatArray?,
                          sx: Float, sy: Float, sz: Float,
                          out: OutputStream, progress: ((Int)->Unit)?) {
        val vc = verts.size / 3; val tc = vc / 3
        val hasN = norms != null && norms.size >= vc*3
        val hdr = buildString {
            append("ply\nformat binary_little_endian 1.0\ncomment 3D Model Viewer\n")
            append("element vertex $vc\nproperty float x\nproperty float y\nproperty float z\n")
            if (hasN) append("property float nx\nproperty float ny\nproperty float nz\n")
            append("element face $tc\nproperty list uchar int vertex_indices\nend_header\n")
        }
        out.write(hdr.toByteArray(Charsets.US_ASCII))
        val buf = java.nio.ByteBuffer.allocate((if (hasN) 24 else 12) * vc + 13 * tc).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until vc) {
            val b=i*3; buf.putFloat(verts[b]*sx); buf.putFloat(verts[b+1]*sy); buf.putFloat(verts[b+2]*sz)
            if (hasN) { buf.putFloat(norms!![b]); buf.putFloat(norms[b+1]); buf.putFloat(norms[b+2]) }
        }
        for (t in 0 until tc) { buf.put(3); buf.putInt(t*3); buf.putInt(t*3+1); buf.putInt(t*3+2) }
        out.write(buf.array(), 0, buf.position())
        progress?.invoke(100)
    }
}
