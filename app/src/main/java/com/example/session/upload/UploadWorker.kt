package com.example.session.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.session.net.ConnectionSettingsStore
import com.example.session.take.TakeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Overridable for tests and app-level configuration. */
object UploadDeps {
    @Volatile
    var httpClient: OkHttpClient = UploadApi.defaultHttpClient()
}

/**
 * Marker the session writes while it is recording. Uploads are deferrable and
 * the monitor stream is real-time, so uploads stay out of the way until the
 * take stops.
 */
object SessionBusyFlag {
    private const val FILE_NAME = "session-recording.flag"

    fun setBusy(rootDir: File, busy: Boolean) {
        val file = File(rootDir, FILE_NAME)
        if (busy) {
            file.parentFile?.mkdirs()
            runCatching { file.writeText(System.currentTimeMillis().toString()) }
        } else {
            file.delete()
        }
    }

    fun isBusy(rootDir: File): Boolean = File(rootDir, FILE_NAME).exists()
}

/**
 * Restart-safe upload of one finished take.
 *
 * Everything the worker needs is on disk: the manifest and chunks in the take
 * folder, the connection and token in [ConnectionSettingsStore]. A reboot or a
 * process kill therefore resumes from the first chunk the server is missing.
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val takeId = inputData.getString(KEY_TAKE_ID)
            ?: return@withContext fail("NO_TAKE_ID")
        if (runAttemptCount > MAX_ATTEMPTS) return@withContext fail("TOO_MANY_ATTEMPTS")

        val root = File(applicationContext.filesDir, SESSION_ROOT_DIR)
        val store = TakeStore(root)

        // Never compete with recording or the monitor stream.
        if (SessionBusyFlag.isBusy(root)) return@withContext Result.retry()

        val connection = ConnectionSettingsStore(root).read()
            ?: return@withContext fail("NO_CONNECTION")
        val manifest = store.readManifest(takeId)
            ?: return@withContext fail("MANIFEST_UNREADABLE")

        val api = UploadApi(
            baseUrl = connection.baseUrl,
            http = UploadDeps.httpClient,
            tokenProvider = { connection.token },
        )

        val outcome = UploadEngine(api, store).upload(manifest) { done, total ->
            // Non-suspending progress report; WorkManager serializes the data.
            setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_STATE to "uploading"))
        }
        when (outcome) {
            is UploadOutcome.Verified -> Result.success(
                workDataOf(KEY_STATE to "verified", KEY_DONE to outcome.chunks),
            )

            is UploadOutcome.Retry -> Result.retry()

            is UploadOutcome.Fatal -> fail(outcome.reason)
        }
    }

    private fun fail(reason: String) = Result.failure(workDataOf(KEY_ERROR to reason))

    companion object {
        const val KEY_TAKE_ID = "takeId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_STATE = "state"
        const val KEY_ERROR = "error"
        const val SESSION_ROOT_DIR = "session"
        private const val MAX_ATTEMPTS = 20
    }
}

/** Enqueues uploads with Wi-Fi-only default and exponential backoff. */
object UploadScheduler {

    fun enqueue(context: Context, takeId: String, unmeteredOnly: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_TAKE_ID to takeId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    )
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("session-upload-$takeId", ExistingWorkPolicy.KEEP, request)
    }
}
