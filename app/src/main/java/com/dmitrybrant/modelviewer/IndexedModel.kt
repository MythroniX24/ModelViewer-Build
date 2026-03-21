package com.dmitrybrant.modelviewer

import android.opengl.GLES20
import java.nio.IntBuffer

open class IndexedModel : ArrayModel() {
    // Exposed so StlExporter can read index data
    var indexBuffer: IntBuffer? = null
        protected set
    var indexCount = 0
        protected set

    override fun drawFunc() {
        if (indexBuffer == null || indexCount == 0) {
            return
        }
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_INT, indexBuffer)
    }

    companion object {
        const val BYTES_PER_INT = 4
    }
}
