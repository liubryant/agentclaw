package ai.cjym.agentclaw.ui.widget

import ai.cjym.agentclaw.databinding.ViewAssetVideoPlayerBinding
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Surface
import android.widget.FrameLayout

class AssetVideoPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val binding = ViewAssetVideoPlayerBinding.inflate(LayoutInflater.from(context), this, true)
    private var assetPath: String? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var playWhenReady = true

    var onPlayingChanged: ((Boolean) -> Unit)? = null

    init {
        binding.videoTexture.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                prepareIfPossible()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                player?.let { updateFitTransform(it.videoWidth, it.videoHeight) }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                release()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    fun setAsset(path: String) {
        if (assetPath == path) return
        assetPath = path
        release()
        prepareIfPossible()
    }

    fun play() {
        playWhenReady = true
        if (!prepared) {
            prepareIfPossible()
            return
        }
        player?.start()
        onPlayingChanged?.invoke(true)
    }

    fun pause() {
        playWhenReady = false
        player?.takeIf { prepared && it.isPlaying }?.pause()
        onPlayingChanged?.invoke(false)
    }

    fun toggle() {
        if (player?.isPlaying == true) pause() else play()
    }

    fun release() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        prepared = false
        onPlayingChanged?.invoke(false)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun prepareIfPossible() {
        val path = assetPath ?: return
        val texture = binding.videoTexture.surfaceTexture ?: return
        if (player != null) return

        val afd = context.assets.openFd(path)
        val surface = Surface(texture)
        player = MediaPlayer().apply {
            setSurface(surface)
            isLooping = true
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            setOnPreparedListener { readyPlayer ->
                prepared = true
                updateFitTransform(readyPlayer.videoWidth, readyPlayer.videoHeight)
                if (playWhenReady) {
                    readyPlayer.start()
                    onPlayingChanged?.invoke(true)
                }
            }
            setOnVideoSizeChangedListener { _, width, height -> updateFitTransform(width, height) }
            setOnErrorListener { _, _, _ ->
                onPlayingChanged?.invoke(false)
                true
            }
            prepareAsync()
        }
        surface.release()
        afd.close()
    }

    private fun updateFitTransform(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = binding.videoTexture.width
        val viewHeight = binding.videoTexture.height
        if (viewWidth == 0 || viewHeight == 0) {
            binding.videoTexture.post { updateFitTransform(videoWidth, videoHeight) }
            return
        }
        val viewAspect = viewWidth.toFloat() / viewHeight
        val videoAspect = videoWidth.toFloat() / videoHeight
        var scaleX = 1f
        var scaleY = 1f
        if (videoAspect > viewAspect) {
            scaleY = viewAspect / videoAspect
        } else {
            scaleX = videoAspect / viewAspect
        }
        binding.videoTexture.setTransform(Matrix().apply {
            setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        })
    }
}
