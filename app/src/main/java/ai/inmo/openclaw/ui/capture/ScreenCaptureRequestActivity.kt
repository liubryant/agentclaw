package ai.inmo.openclaw.ui.capture

import ai.inmo.openclaw.service.capture.ScreenCaptureService
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScreenCaptureRequestActivity : AppCompatActivity() {
    private var pollJob: Job? = null
    private val activityScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_CAPTURE) return

        if (resultCode != Activity.RESULT_OK || data == null) {
            ScreenCaptureCoordinator.complete(null)
            finish()
            return
        }

        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 5000L)
        ScreenCaptureService.clearResult()
        val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
            putExtra("durationMs", durationMs)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        pollJob?.cancel()
        pollJob = activityScope.launch {
            val timeoutMs = durationMs + 5000L
            val start = System.currentTimeMillis()
            while (ScreenCaptureService.resultPath == null && System.currentTimeMillis() - start < timeoutMs) {
                delay(200)
            }
            val result = ScreenCaptureService.resultPath
            ScreenCaptureCoordinator.complete(result)
            finish()
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DURATION_MS = "durationMs"
        private const val REQUEST_CODE_CAPTURE = 3101
    }
}
