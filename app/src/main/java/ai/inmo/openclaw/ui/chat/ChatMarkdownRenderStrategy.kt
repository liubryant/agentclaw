package ai.inmo.openclaw.ui.chat

internal object ChatMarkdownRenderStrategy {

    enum class Mode {
        PRIMARY,
        FALLBACK,
        PLAIN_TEXT
    }

    data class Result<T>(
        val value: T,
        val mode: Mode,
        val primaryError: Throwable? = null,
        val fallbackError: Throwable? = null
    )

    inline fun <T> render(
        content: String,
        primary: () -> T,
        fallback: () -> T,
        plainText: (String) -> T
    ): Result<T> {
        return try {
            Result(
                value = primary(),
                mode = Mode.PRIMARY
            )
        } catch (primaryError: Throwable) {
            try {
                Result(
                    value = fallback(),
                    mode = Mode.FALLBACK,
                    primaryError = primaryError
                )
            } catch (fallbackError: Throwable) {
                Result(
                    value = plainText(content),
                    mode = Mode.PLAIN_TEXT,
                    primaryError = primaryError,
                    fallbackError = fallbackError
                )
            }
        }
    }

    fun preview(content: String, maxLength: Int = 120): String {
        val normalized = content
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maxLength) return normalized
        return normalized.take((maxLength - 3).coerceAtLeast(0)) + "..."
    }
}

internal object ChatMarkdownRenderCache {
    inline fun <T> getOrPut(
        cacheKey: String,
        get: (String) -> T?,
        put: (String, T) -> Unit,
        build: () -> T
    ): T {
        val cached = get(cacheKey)
        if (cached != null) return cached
        return build().also { put(cacheKey, it) }
    }
}
