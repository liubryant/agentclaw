package ai.inmo.openclaw.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownRenderStrategyTest {

    @Test
    fun render_returnsPrimaryResult_whenPrimarySucceeds() {
        val result = ChatMarkdownRenderStrategy.render(
            content = "hello",
            primary = { "primary" },
            fallback = { "fallback" },
            plainText = { "plain:$it" }
        )

        assertEquals(ChatMarkdownRenderStrategy.Mode.PRIMARY, result.mode)
        assertEquals("primary", result.value)
    }

    @Test
    fun render_returnsFallback_whenPrimaryThrows() {
        val primaryError = IllegalStateException("broken table")

        val result = ChatMarkdownRenderStrategy.render(
            content = "| bad | table |",
            primary = { throw primaryError },
            fallback = { "fallback" },
            plainText = { "plain:$it" }
        )

        assertEquals(ChatMarkdownRenderStrategy.Mode.FALLBACK, result.mode)
        assertEquals("fallback", result.value)
        assertSame(primaryError, result.primaryError)
    }

    @Test
    fun render_returnsPlainText_whenPrimaryAndFallbackThrow() {
        val primaryError = IllegalStateException("primary")
        val fallbackError = IllegalArgumentException("fallback")

        val result = ChatMarkdownRenderStrategy.render(
            content = "raw text",
            primary = { throw primaryError },
            fallback = { throw fallbackError },
            plainText = { "plain:$it" }
        )

        assertEquals(ChatMarkdownRenderStrategy.Mode.PLAIN_TEXT, result.mode)
        assertEquals("plain:raw text", result.value)
        assertSame(primaryError, result.primaryError)
        assertSame(fallbackError, result.fallbackError)
    }

    @Test
    fun preview_normalizesWhitespace_andTruncates() {
        val preview = ChatMarkdownRenderStrategy.preview(
            "line1\n\nline2   line3",
            maxLength = 12
        )

        assertEquals("line1 lin...", preview)
    }

    @Test
    fun cache_returnsExistingValue_withoutRebuilding() {
        val cache = linkedMapOf("message-1" to "cached")
        var buildCount = 0

        val result = ChatMarkdownRenderCache.getOrPut(
            cacheKey = "message-1",
            get = cache::get,
            put = { key, value -> cache[key] = value }
        ) {
            buildCount++
            "new-value"
        }

        assertEquals("cached", result)
        assertEquals(0, buildCount)
    }

    @Test
    fun cache_storesFallbackValue_forNextLookup() {
        val cache = linkedMapOf<String, String>()
        var primaryCalls = 0

        val first = ChatMarkdownRenderCache.getOrPut(
            cacheKey = "message-2",
            get = cache::get,
            put = { key, value -> cache[key] = value }
        ) {
            ChatMarkdownRenderStrategy.render(
                content = "| broken |",
                primary = {
                    primaryCalls++
                    throw NullPointerException("TableRowSpan")
                },
                fallback = { "fallback" },
                plainText = { it }
            ).value
        }

        val second = ChatMarkdownRenderCache.getOrPut(
            cacheKey = "message-2",
            get = cache::get,
            put = { key, value -> cache[key] = value }
        ) {
            ChatMarkdownRenderStrategy.render(
                content = "| broken |",
                primary = {
                    primaryCalls++
                    throw NullPointerException("TableRowSpan")
                },
                fallback = { "fallback-again" },
                plainText = { it }
            ).value
        }

        assertEquals("fallback", first)
        assertEquals("fallback", second)
        assertEquals(1, primaryCalls)
        assertTrue(cache.containsKey("message-2"))
    }
}
