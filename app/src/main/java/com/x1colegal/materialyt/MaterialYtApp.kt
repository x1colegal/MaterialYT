package com.x1colegal.materialyt

import android.app.Application

class MaterialYtApp : Application() {
    companion object { lateinit var instance: MaterialYtApp }
    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.event("application started version=1.1.0 process=${android.os.Process.myPid()}")
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.failure("uncaught thread=${thread.name}", error)
            kotlin.system.exitProcess(10)
        }
        HttpBackend.initialize(this)
        EjsChallengeSolver.initialize(this)
    }
}
