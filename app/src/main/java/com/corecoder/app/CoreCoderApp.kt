package com.corecoder.app

import android.app.Application
import com.corecoder.app.data.AppDatabase
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CoreCoderApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
}
