package com.projecttavern.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class TavernApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Store.applyAppearance()
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
    }
}
