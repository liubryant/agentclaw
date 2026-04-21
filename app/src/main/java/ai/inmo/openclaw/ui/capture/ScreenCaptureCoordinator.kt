package ai.inmo.openclaw.ui.capture

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred

object ScreenCaptureCoordinator {
    @Volatile
    private var pendingResult: CompletableDeferred<String?>? = null

    suspend fun requestCapture(context: Context, durationMs: Long): String? {
        val deferred = CompletableDeferred<String?>()
        pendingResult = deferred
        val intent = Intent(context, ScreenCaptureRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ScreenCaptureRequestActivity.EXTRA_DURATION_MS, durationMs)
        }
        context.startActivity(intent)
        return deferred.await()
    }

    fun complete(path: String?) {
        pendingResult?.complete(path)
        pendingResult = null
    }
}
