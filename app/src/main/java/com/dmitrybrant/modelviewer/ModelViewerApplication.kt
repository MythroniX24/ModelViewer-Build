package com.dmitrybrant.modelviewer

import android.app.Application
import android.net.Uri

class ModelViewerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: ModelViewerApplication
            private set
        var currentModel: Model? = null
        var currentModelUri: Uri? = null
        var currentModelFileSize: Long = 0L
        var wantDecimate: Boolean = false
    }
}
