package com.dmitrybrant.modelviewer.obj

import android.content.ContentResolver
import android.net.Uri
import com.dmitrybrant.modelviewer.ArrayModel
import com.dmitrybrant.modelviewer.NativeModelLoader
import java.io.IOException
import java.io.InputStream

class ObjModel : ArrayModel {

    constructor(uri: Uri, cr: ContentResolver, fileSize: Long, cb: ((Int)->Unit)? = null) : super() {
        this.fileSizeBytes = fileSize
        val pfd = cr.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
        pfd.use {
            val h = NativeModelLoader.loadFromFd(it.fd, 0L, fileSize, ".obj", cb)
            if (!NativeModelLoader.fillModel(h, this, cb)) throw IOException("Failed to load OBJ")
        }
    }

    constructor(stream: InputStream) : super() {
        val bytes = stream.readBytes()
        val h = NativeModelLoader.loadFromBytes(bytes, ".obj", null)
        if (!NativeModelLoader.fillModel(h, this, null)) throw IOException("Failed to load OBJ")
    }

    public override fun initModelMatrix(boundSize: Float) {
        initModelMatrix(boundSize, -90f, 0f, 180f)
        var s = getBoundScale(boundSize); if (s == 0f) s = 1f
        floorOffset = (minZ - centerMassZ) / s
    }
}
