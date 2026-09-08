package com.winlator.cmod.feature.stores.ea.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.winlator.cmod.feature.stores.common.StoreAuthStatus
import timber.log.Timber
import java.util.concurrent.TimeUnit

class EaTokenRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            when (EaAuthManager.getAuthStatus(ctx)) {
                StoreAuthStatus.LOGGED_OUT, StoreAuthStatus.EXPIRED -> {
                    cancel(ctx)
                    Result.success()
                }
                else -> {
                    if (EaAuthManager.getValidAccessToken(ctx) != null) {
                        Result.success()
                    } else {
                        Result.retry()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "EA background refresh threw")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EA"
        private const val WORK_NAME = "ea_token_refresh"
        private const val MIN_INTERVAL_MINUTES = 15L
        private const val DEFAULT_INTERVAL_MINUTES = 120L

        fun schedule(context: Context, expiresInSeconds: Int) {
            val halfLifeMinutes = if (expiresInSeconds > 0) expiresInSeconds / 120L else 0L
            val intervalMinutes =
                when {
                    halfLifeMinutes >= MIN_INTERVAL_MINUTES -> halfLifeMinutes
                    halfLifeMinutes > 0L -> MIN_INTERVAL_MINUTES
                    else -> DEFAULT_INTERVAL_MINUTES
                }
            val request =
                PeriodicWorkRequestBuilder<EaTokenRefreshWorker>(intervalMinutes, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
