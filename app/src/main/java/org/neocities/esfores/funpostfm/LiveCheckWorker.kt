package org.neocities.esfores.funpostfm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** A look at the station while the app is closed, for the "we're live" ping. */
class LiveCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        App.publish(applicationContext, Station.check())
        return Result.success()
    }
}
