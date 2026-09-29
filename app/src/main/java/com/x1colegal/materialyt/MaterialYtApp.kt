package com.x1colegal.materialyt

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe

class MaterialYtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.event("application started version=1.1.0 process=${android.os.Process.myPid()}")
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.failure("uncaught thread=${thread.name}", error)
            kotlin.system.exitProcess(10)
        }
        HttpBackend.initialize(this)
        NewPipe.init(HttpBackend)
        EjsChallengeSolver.initialize(this)
    }
}
