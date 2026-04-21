package ai.inmo.openclaw.ui.chat

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.util.LruCache
import ai.inmo.core_common.utils.Logger
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ChatMarkdownProvider {
    @Volatile
    private var instance: Markwon? = null
    @Volatile
    private var fallbackInstance: Markwon? = null
    private val spannedCache = LruCache<String, Spanned>(160)
    private val precomputedCache = LruCache<String, PrecomputedTextCompat>(160)
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun get(context: Context): Markwon {
        val appContext = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: buildMarkwon(appContext, includeTables = true).also { instance = it }
        }
    }

    private fun getFallback(context: Context): Markwon {
        val appContext = context.applicationContext
        return fallbackInstance ?: synchronized(this) {
            fallbackInstance ?: buildMarkwon(appContext, includeTables = false)
                .also { fallbackInstance = it }
        }
    }

    fun getCached(cacheKey: String): Spanned? = spannedCache.get(cacheKey)

    fun getPrecomputed(cacheKey: String): PrecomputedTextCompat? = precomputedCache.get(cacheKey)

    fun render(context: Context, cacheKey: String, content: String): Spanned {
        return ChatMarkdownRenderCache.getOrPut(
            cacheKey = cacheKey,
            get = spannedCache::get,
            put = { key, value -> spannedCache.put(key, value) }
        ) {
            renderMarkdownSafely(context, cacheKey, content)
        }
    }

    fun buildPrecomputedCacheKey(messageId: String, contentHash: Int, widthBucket: Int): String {
        return "$messageId:$contentHash:$widthBucket"
    }

    fun ensurePrecomputed(
        cacheKey: String,
        markdown: CharSequence,
        params: PrecomputedTextCompat.Params
    ): PrecomputedTextCompat {
        val cached = precomputedCache.get(cacheKey)
        if (cached != null) return cached
        return PrecomputedTextCompat.create(markdown, params).also {
            precomputedCache.put(cacheKey, it)
        }
    }

    fun preload(context: Context, items: List<ChatMessageItem>) {
        val markdownItems = items.filterIsInstance<ChatMessageItem.AssistantMessageItem>()
            .filter { !it.isStreaming && it.content.isNotBlank() }
            .take(MAX_PRELOAD_ASSISTANTS)
        if (markdownItems.isEmpty()) return
        val appContext = context.applicationContext
        warmupScope.launch {
            markdownItems.forEach { item ->
                if (spannedCache.get(item.id) == null) {
                    render(appContext, item.id, item.content)
                }
            }
        }
    }

    fun preloadPrecomputed(
        context: Context,
        items: List<ChatMessageItem>,
        bubbleWidthPx: Int,
        textSizePx: Float,
        lineSpacingExtraPx: Float
    ) {
        if (bubbleWidthPx <= 0) return
        val markdownItems = items.filterIsInstance<ChatMessageItem.AssistantMessageItem>()
            .filter { !it.isStreaming && it.content.isNotBlank() }
            .take(MAX_PRELOAD_ASSISTANTS)
        if (markdownItems.isEmpty()) return
        val appContext = context.applicationContext
        val textView = android.widget.TextView(appContext).apply {
            textSize = textSizePx / appContext.resources.displayMetrics.scaledDensity
            setLineSpacing(lineSpacingExtraPx, 1f)
            includeFontPadding = false
            maxWidth = bubbleWidthPx
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL
        }
        val params = TextViewCompat.getTextMetricsParams(textView)
        val widthBucket = bubbleWidthPx / WIDTH_BUCKET_STEP_PX
        warmupScope.launch {
            markdownItems.forEach { item ->
                val markdown = render(appContext, item.id, item.content)
                val cacheKey = buildPrecomputedCacheKey(item.id, item.content.hashCode(), widthBucket)
                if (precomputedCache.get(cacheKey) == null) {
                    runCatching {
                        precomputedCache.put(cacheKey, PrecomputedTextCompat.create(markdown, params))
                    }
                }
            }
        }
    }

    private fun buildMarkwon(context: Context, includeTables: Boolean): Markwon {
        return Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .also { builder ->
                if (includeTables) {
                    builder.usePlugin(TablePlugin.create(context))
                }
            }
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeBlockTypeface(Typeface.MONOSPACE)
                        .codeTypeface(Typeface.MONOSPACE)
                        .codeBlockMargin(16)
                        .codeTextSize((context.resources.displayMetrics.scaledDensity * 13).toInt())
                }
            })
            .build()
    }

    private fun renderMarkdownSafely(
        context: Context,
        cacheKey: String,
        content: String
    ): Spanned {
        val result = ChatMarkdownRenderStrategy.render(
            content = content,
            primary = { get(context).toMarkdown(content) },
            fallback = { getFallback(context).toMarkdown(content) },
            plainText = ::SpannableString
        )

        when (result.mode) {
            ChatMarkdownRenderStrategy.Mode.PRIMARY -> Unit
            ChatMarkdownRenderStrategy.Mode.FALLBACK -> {
                Logger.w(
                    TAG,
                    "Markdown fallback without tables, cacheKey=$cacheKey, " +
                        "cause=${result.primaryError?.javaClass?.simpleName}: ${result.primaryError?.message}, " +
                        "preview=${ChatMarkdownRenderStrategy.preview(content)}"
                )
            }
            ChatMarkdownRenderStrategy.Mode.PLAIN_TEXT -> {
                Logger.e(
                    TAG,
                    "Markdown fallback to plain text, cacheKey=$cacheKey, " +
                        "primary=${result.primaryError?.javaClass?.simpleName}: ${result.primaryError?.message}, " +
                        "fallback=${result.fallbackError?.javaClass?.simpleName}: ${result.fallbackError?.message}, " +
                        "preview=${ChatMarkdownRenderStrategy.preview(content)}"
                )
            }
        }

        return result.value
    }

    private const val MAX_PRELOAD_ASSISTANTS = 12
    private const val WIDTH_BUCKET_STEP_PX = 24
    private const val TAG = "ChatMarkdownProvider"
}
