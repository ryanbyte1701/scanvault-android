package com.scanvault.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.scanvault.android.data.AppContainer

class ScanVaultApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Decrypted copies opened in other apps are temporary; clear any left from a previous run.
        container.clearSharedCache()
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}
