package com.x1colegal.materialyt

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

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
        NewPipe.init(HttpBackend)
        YoutubeStreamExtractor.setPoTokenProvider(NewPipePoTokenProvider)
        YoutubeStreamExtractor.setStreamUrlResolver(YouTubeRepository::resolveNewPipeStreamUrl)
        EjsChallengeSolver.initialize(this)
    }
}
