package com.dmitrybrant.modelviewer.stl

import android.content.ContentResolver
import android.net.Uri
import com.dmitrybrant.modelviewer.ArrayModel
import com.dmitrybrant.modelviewer.NativeModelLoader
import java.io.IOException
import java.io.InputStream

class StlModel : ArrayModel {
    companion object { const val LARGE_FILE_THRESHOLD = 100L * 1024 * 1024 }

    constructor(uri: Uri, cr: ContentResolver, fileSize: Long, cb: ((Int) -> Unit)?) : super() {
        this.fileSizeBytes = fileSize
        val pfd = cr.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
        pfd.use {
            val h = NativeModelLoader.loadFromFd(it.fd, 0L, fileSize, ".stl", cb)
            if (!NativeModelLoader.fillModel(h, this, cb)) throw IOException("Failed to load STL")
        }
    }

    constructor(stream: InputStream, fileSize: Long = 0L, cb: ((Int) -> Unit)? = null) : super() {
        this.fileSizeBytes = fileSize
        val bytes = stream.readBytes()
        val h = NativeModelLoader.loadFromBytes(bytes, ".stl", cb)
        if (!NativeModelLoader.fillModel(h, this, cb)) throw IOException("Failed to load STL")
    }

    public override fun initModelMatrix(boundSize: Float) {
        initModelMatrix(boundSize, -90f, 0f, 180f)
        var s = getBoundScale(boundSize); if (s == 0f) s = 1f
        floorOffset = (minZ - centerMassZ) / s
    }
}
