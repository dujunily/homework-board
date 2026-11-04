package cn.yiweiqiu.homeworkboard

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.yiweiqiu.homework.core.DayKeys
import cn.yiweiqiu.homework.core.HomeworkRepository
import java.util.concurrent.TimeUnit

/**
 * WorkManager 兜底：每 6 小时拉一次。
 *
 * 前台轮询只在 App 活着时有效；电视关机、或者一直停在别的应用上时，
 * 靠它保证「第二天开机就有新作业」，不用孩子自己去点什么。
 */
class TvSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = HomeworkRepository(applicationContext)
        return when (val r = repository.refresh(DayKeys.today(), quiet = true)) {
            is HomeworkRepository.RefreshResult.Ok -> Result.success()
            is HomeworkRepository.RefreshResult.Failed -> when (r.kind) {
                // 认证/配置问题重试无用，等家长去改
                HomeworkRepository.Kind.NEED_CONFIG,
                HomeworkRepository.Kind.AUTH,
                -> Result.success()

                else -> Result.retry()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "homeworkboard-tv-periodic"

        fun ensure(context: Context) {
            val request = PeriodicWorkRequestBuilder<TvSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
