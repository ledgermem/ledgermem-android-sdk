package dev.proofly.ledgermem

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync via WorkManager.
 *
 * Integrators provide a concrete [Provider] that returns the configured
 * client + cache; `LedgerMemSync.schedule(context)` enqueues a work request
 * that runs roughly every 30 minutes when the device has connectivity.
 */
public object LedgerMemSync {

    public interface Provider {
        public fun client(): LedgerMemClient
        public fun cache(): MemoryCache
    }

    @Volatile
    private var provider: Provider? = null

    public fun install(provider: Provider) {
        this.provider = provider
    }

    internal fun current(): Provider =
        provider ?: error("LedgerMemSync.install(provider) must be called before scheduling sync")

    public const val WORK_NAME: String = "dev.proofly.ledgermem.sync"

    public fun schedule(context: Context, intervalMinutes: Long = 30) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    public fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

public class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val provider = LedgerMemSync.current()
        val page = provider.client().list(limit = 50)
        provider.cache().upsertAll(page.memories)
        Result.success()
    } catch (err: LedgerMemException.Transport) {
        Result.retry()
    } catch (err: LedgerMemException.Http) {
        if (err.status in 500..599) Result.retry() else Result.failure()
    } catch (err: Throwable) {
        Result.failure()
    }
}
