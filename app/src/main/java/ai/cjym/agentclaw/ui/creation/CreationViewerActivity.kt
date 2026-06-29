package ai.cjym.agentclaw.ui.creation

import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.databinding.ActivityCreationViewerBinding
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class CreationViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCreationViewerBinding
    private lateinit var media: CreationMedia

    private var videoScale = 1f
    private var videoTransX = 0f
    private var videoTransY = 0f
    private lateinit var videoScaleDetector: ScaleGestureDetector
    private lateinit var videoTapDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreationViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterFullscreen()

        val path = intent.getStringExtra(EXTRA_ASSET_PATH).orEmpty()
        val type = runCatching {
            CreationMedia.Type.valueOf(intent.getStringExtra(EXTRA_MEDIA_TYPE).orEmpty())
        }.getOrDefault(CreationMedia.Type.IMAGE)
        if (path.isBlank()) {
            finish()
            return
        }
        media = CreationMedia(path, type)
        setupVideoGestures()
        renderMedia()

        binding.closeButton.setOnClickListener { finishViewer() }
        binding.playPauseButton.setOnClickListener { binding.videoPlayer.toggle() }
        binding.createSameButton.setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_MEDIA_TYPE, media.type.name))
            finishViewer()
        }
    }

    private fun setupVideoGestures() {
        videoScaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    videoScale = (videoScale * detector.scaleFactor).coerceIn(1f, 5f)
                    applyVideoTransform()
                    return true
                }
            })

        videoTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (videoScale > 1.2f) 1f else 2f
                binding.videoPlayer.animate()
                    .scaleX(target).scaleY(target)
                    .translationX(0f).translationY(0f)
                    .setDuration(220).start()
                videoScale = target
                videoTransX = 0f
                videoTransY = 0f
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                binding.videoPlayer.toggle()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (videoScale > 1f && (e2.pointerCount == 1)) {
                    videoTransX -= distanceX
                    videoTransY -= distanceY
                    clampVideoTranslation()
                    applyVideoTransform()
                    return true
                }
                return false
            }
        })

        binding.videoPlayer.setOnTouchListener { _, event ->
            videoScaleDetector.onTouchEvent(event)
            if (!videoScaleDetector.isInProgress) {
                videoTapDetector.onTouchEvent(event)
            }
            true
        }
    }

    private fun clampVideoTranslation() {
        val maxX = binding.videoPlayer.width * (videoScale - 1f) / 2f
        val maxY = binding.videoPlayer.height * (videoScale - 1f) / 2f
        videoTransX = videoTransX.coerceIn(-maxX, maxX)
        videoTransY = videoTransY.coerceIn(-maxY, maxY)
    }

    private fun applyVideoTransform() {
        binding.videoPlayer.scaleX = videoScale
        binding.videoPlayer.scaleY = videoScale
        binding.videoPlayer.translationX = videoTransX
        binding.videoPlayer.translationY = videoTransY
    }

    override fun onResume() {
        super.onResume()
        if (::media.isInitialized && media.type == CreationMedia.Type.VIDEO) binding.videoPlayer.play()
    }

    override fun onPause() {
        if (::media.isInitialized && media.type == CreationMedia.Type.VIDEO) binding.videoPlayer.pause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.videoPlayer.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = finishViewer()

    private fun renderMedia() {
        val isVideo = media.type == CreationMedia.Type.VIDEO
        binding.imagePreview.visibility = if (isVideo) View.GONE else View.VISIBLE
        binding.videoPlayer.visibility = if (isVideo) View.VISIBLE else View.GONE
        binding.playPauseButton.visibility = if (isVideo) View.VISIBLE else View.GONE
        binding.imageLoading.visibility = if (isVideo) View.GONE else View.VISIBLE

        if (isVideo) {
            binding.videoPlayer.onPlayingChanged = { playing ->
                binding.playPauseButton.setImageResource(
                    if (playing) R.drawable.ic_creation_pause else R.drawable.ic_creation_play
                )
                binding.playPauseButton.contentDescription = getString(
                    if (playing) R.string.creation_pause_video else R.string.creation_play_video
                )
                binding.playPauseButton.animate().alpha(if (playing) 0.32f else 1f).setDuration(200L).start()
            }
            binding.videoPlayer.setAsset(media.assetPath)
        } else {
            binding.imageLoading.visibility = View.VISIBLE
            CreationAssetLoader.loadFullImage(this, media.assetPath) { bitmap ->
                if (isFinishing || isDestroyed) return@loadFullImage
                binding.imagePreview.setImageBitmap(bitmap)
                binding.imageLoading.animate().alpha(0f).setDuration(180L).withEndAction {
                    binding.imageLoading.visibility = View.GONE
                }.start()
                binding.imagePreview.alpha = 0f
                binding.imagePreview.animate().alpha(1f).setDuration(320L).start()
            }
        }
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun finishViewer() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    companion object {
        const val EXTRA_ASSET_PATH = "creation_asset_path"
        const val EXTRA_MEDIA_TYPE = "creation_media_type"

        fun createIntent(context: Context, media: CreationMedia): Intent {
            return Intent(context, CreationViewerActivity::class.java)
                .putExtra(EXTRA_ASSET_PATH, media.assetPath)
                .putExtra(EXTRA_MEDIA_TYPE, media.type.name)
        }
    }
}
