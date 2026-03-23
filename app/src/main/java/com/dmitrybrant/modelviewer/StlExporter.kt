package com.dmitrybrant.modelviewer

import android.content.ContentResolver
import android.net.Uri
import java.io.OutputStream

object StlExporter {
    fun export(model: Model, outputStream: OutputStream,
               contentResolver: ContentResolver? = null,
               originalUri: Uri? = null,
               progressCallback: ((Int) -> Unit)? = null) {
        ModelExporter.export(model, outputStream, "stl",
            contentResolver, originalUri, progressCallback)
    }
}
