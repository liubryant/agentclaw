package ai.inmo.openclaw.ui.widget

import ai.inmo.openclaw.databinding.ViewLoopingVideoBinding
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Surface
import android.widget.FrameLayout
import androidx.annotation.RawRes

class LoopingVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val binding = ViewLoopingVideoBinding.inflate(LayoutInflater.from(context), this, true)

    @RawRes
    private var videoResId: Int? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isVideoPrepared = false
    private var shouldPlayWhenReady = false

    private val surfaceListener = object : android.view.TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            preparePlayerIfPossible()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            mediaPlayer?.let { updateVideoTransform(it.videoWidth, it.videoHeight) }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            release()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    init {
        binding.textureView.surfaceTextureListener = surfaceListener
    }

    fun setVideoResource(@RawRes resId: Int) {
        if (videoResId == resId) return
        videoResId = resId
        release()
        preparePlayerIfPossible()
    }

    fun play() {
        shouldPlayWhenReady = true
        if (mediaPlayer == null) {
            preparePlayerIfPossible()
            return
        }
        if (isVideoPrepared) {
            mediaPlayer?.start()
        }
    }

    fun pause() {
        shouldPlayWhenReady = false
        mediaPlayer?.takeIf { isVideoPrepared && it.isPlaying }?.pause()
    }

    fun release() {
        mediaPlayer?.runCatching {
            stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
        isVideoPrepared = false
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun preparePlayerIfPossible() {
        val resId = videoResId ?: return
        val surfaceTexture = binding.textureView.surfaceTexture ?: return
        if (mediaPlayer != null) return

        val afd = resources.openRawResourceFd(resId) ?: return
        val surface = Surface(surfaceTexture)
        isVideoPrepared = false
        mediaPlayer = MediaPlayer().apply {
            setSurface(surface)
            isLooping = true
            setVolume(0f, 0f)
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            setOnPreparedListener { player ->
                isVideoPrepared = true
                updateVideoTransform(player.videoWidth, player.videoHeight)
                if (shouldPlayWhenReady) {
                    player.start()
                }
            }
            setOnVideoSizeChangedListener { _, width, height ->
                updateVideoTransform(width, height)
            }
            prepareAsync()
        }
        surface.release()
        afd.close()
    }

    private fun updateVideoTransform(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = binding.textureView.width
        val viewHeight = binding.textureView.height
        if (viewWidth == 0 || viewHeight == 0) {
            binding.textureView.post { updateVideoTransform(videoWidth, videoHeight) }
            return
        }

        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        var scaleX = 1f
        var scaleY = 1f

        if (kotlin.math.abs(videoAspect - viewAspect) > 0.001f) {
            if (videoAspect > viewAspect) {
                scaleX = videoAspect / viewAspect
            } else {
                scaleY = viewAspect / videoAspect
            }
        }

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        binding.textureView.setTransform(
            Matrix().apply {
                setScale(scaleX, scaleY, centerX, centerY)
            }
        )
    }
}
