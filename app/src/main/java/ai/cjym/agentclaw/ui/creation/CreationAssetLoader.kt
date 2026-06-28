package ai.cjym.agentclaw.ui.creation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors
import kotlin.math.max

object CreationAssetLoader {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun loadThumbnail(imageView: ImageView, media: CreationMedia, onLoaded: ((Bitmap) -> Unit)? = null) {
        val key = "thumb:${media.type}:${media.assetPath}"
        imageView.tag = key
        cache.get(key)?.let {
            imageView.setImageBitmap(it)
            onLoaded?.invoke(it)
            return
        }
        imageView.setImageDrawable(null)
        executor.execute {
            val bitmap = runCatching {
                when (media.type) {
                    CreationMedia.Type.IMAGE -> decodeImageAsset(imageView.context, media.assetPath, 720, 720)
                    CreationMedia.Type.VIDEO -> decodeVideoFrame(imageView.context, media.assetPath, 720, 720)
                }
            }.getOrNull() ?: return@execute
            cache.put(key, bitmap)
            mainHandler.post {
                if (imageView.tag == key) {
                    imageView.setImageBitmap(bitmap)
                    onLoaded?.invoke(bitmap)
                }
            }
        }
    }

    fun loadFullImage(context: Context, assetPath: String, onLoaded: (Bitmap?) -> Unit) {
        val key = "full:$assetPath"
        cache.get(key)?.let {
            onLoaded(it)
            return
        }
        executor.execute {
            val metrics = context.resources.displayMetrics
            val bitmap = runCatching {
                decodeImageAsset(context, assetPath, metrics.widthPixels * 2, metrics.heightPixels * 2)
            }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            mainHandler.post { onLoaded(bitmap) }
        }
    }

    private fun decodeImageAsset(context: Context, path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
        }
        return context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun decodeVideoFrame(context: Context, path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            context.assets.openFd(path).use { afd ->
                retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                var sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: reqWidth
                var sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: reqHeight
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) {
                    val originalWidth = sourceWidth
                    sourceWidth = sourceHeight
                    sourceHeight = originalWidth
                }
                val ratio = minOf(reqWidth.toFloat() / sourceWidth, reqHeight.toFloat() / sourceHeight, 1f)
                retriever.getScaledFrameAtTime(
                    750_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    max(1, (sourceWidth * ratio).toInt()),
                    max(1, (sourceHeight * ratio).toInt())
                )
            } else {
                retriever.getFrameAtTime(750_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { source ->
                    val ratio = minOf(reqWidth.toFloat() / source.width, reqHeight.toFloat() / source.height, 1f)
                    val scaled = Bitmap.createScaledBitmap(
                        source,
                        max(1, (source.width * ratio).toInt()),
                        max(1, (source.height * ratio).toInt()),
                        true
                    )
                    if (scaled !== source) source.recycle()
                    scaled
                }
            }
        } finally {
            retriever.release()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sample = 1
        if (width <= 0 || height <= 0) return sample
        while (height / (sample * 2) >= reqHeight && width / (sample * 2) >= reqWidth) {
            sample *= 2
        }
        return sample
    }
}
