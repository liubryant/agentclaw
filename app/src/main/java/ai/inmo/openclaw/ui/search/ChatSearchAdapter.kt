package ai.inmo.openclaw.ui.search

import ai.inmo.openclaw.R
import ai.inmo.openclaw.data.repository.ChatSearchResults
import ai.inmo.openclaw.data.repository.SearchMatchItem
import ai.inmo.openclaw.data.repository.SearchSessionGroup
import ai.inmo.openclaw.data.repository.SearchSessionMatch
import ai.inmo.openclaw.databinding.ItemSearchResultRowBinding
import ai.inmo.openclaw.databinding.ItemSearchSectionHeaderBinding
import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale

sealed class SearchResultUiItem {
    abstract val stableId: String

    data class DateHeader(
        override val stableId: String,
        val title: String
    ) : SearchResultUiItem()

    data class ResultRow(
        override val stableId: String,
        val sessionKey: String,
        val title: CharSequence,
        val summary: CharSequence,
        val timestamp: Long
    ) : SearchResultUiItem()
}

class ChatSearchAdapter : ListAdapter<SearchResultUiItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {
    var onResultClick: ((String) -> Unit)? = null
    private var currentContext: Context? = null
    private var highlightColor: Int = 0

    fun submitResults(results: ChatSearchResults, query: String) {
        submitList(buildItems(results, query))
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SearchResultUiItem.DateHeader -> VIEW_TYPE_HEADER
            is SearchResultUiItem.ResultRow -> VIEW_TYPE_RESULT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderHolder(
                ItemSearchSectionHeaderBinding.inflate(inflater, parent, false)
            )

            else -> ResultHolder(
                ItemSearchResultRowBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchResultUiItem.DateHeader -> (holder as HeaderHolder).bind(item)
            is SearchResultUiItem.ResultRow -> {
                val isLastResult = currentList.drop(position + 1)
                    .none { it is SearchResultUiItem.ResultRow }
                (holder as ResultHolder).bind(item, isLastResult, onResultClick)
            }
        }
    }

    private fun buildItems(results: ChatSearchResults, query: String): List<SearchResultUiItem> {
        val flatResults = buildList {
            addAll(results.sessionMatches.map { it.toResultRow(query) })
            addAll(results.messageMatches.flatMap { it.toResultRows(query) })
            addAll(results.toolCallMatches.flatMap { it.toResultRows(query) })
        }.sortedByDescending { it.timestamp }

        if (flatResults.isEmpty()) return emptyList()

        val context = currentContext ?: return flatResults
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monthStart = today.withDayOfMonth(1)

        val grouped = linkedMapOf(
            context.getString(R.string.chat_sessions_today) to mutableListOf<SearchResultUiItem.ResultRow>(),
            context.getString(R.string.chat_sessions_this_week) to mutableListOf<SearchResultUiItem.ResultRow>(),
            context.getString(R.string.chat_sessions_this_month) to mutableListOf<SearchResultUiItem.ResultRow>(),
            context.getString(R.string.chat_sessions_earlier) to mutableListOf<SearchResultUiItem.ResultRow>()
        )

        flatResults.forEach { row ->
            val updatedDate = Instant.ofEpochMilli(row.timestamp)
                .atZone(zoneId)
                .toLocalDate()
            val bucket = when {
                updatedDate == today -> context.getString(R.string.chat_sessions_today)
                !updatedDate.isBefore(weekStart) -> context.getString(R.string.chat_sessions_this_week)
                !updatedDate.isBefore(monthStart) -> context.getString(R.string.chat_sessions_this_month)
                else -> context.getString(R.string.chat_sessions_earlier)
            }
            grouped.getValue(bucket).add(row)
        }

        return buildList {
            grouped.forEach { (title, rows) ->
                if (rows.isEmpty()) return@forEach
                add(SearchResultUiItem.DateHeader("header:$title", title))
                addAll(rows)
            }
        }
    }

    private fun SearchSessionMatch.toResultRow(query: String): SearchResultUiItem.ResultRow {
        return SearchResultUiItem.ResultRow(
            stableId = "session:$sessionKey",
            sessionKey = sessionKey,
            title = highlightQuery(sessionTitle, query),
//            summary = contextString(R.string.chat_search_session_title_match),
            summary = "",
            timestamp = updatedAt
        )
    }

    private fun SearchSessionGroup.toResultRows(query: String): List<SearchResultUiItem.ResultRow> {
        return matches.mapIndexed { index, match ->
            SearchResultUiItem.ResultRow(
                stableId = "content:$sessionKey:${match.matchType}:$index:${match.targetMessageIndex}",
                sessionKey = sessionKey,
                title = highlightQuery(sessionTitle, query),
                summary = highlightSnippet(match, query),
                timestamp = match.createdAt
            )
        }
    }

    private fun highlightSnippet(match: SearchMatchItem, query: String): CharSequence {
        val source = HtmlCompat.fromHtml(match.snippet, HtmlCompat.FROM_HTML_MODE_LEGACY)
        return highlightSpanned(source, query)
    }

    private fun highlightQuery(text: String, query: String): CharSequence {
        return highlightSpanned(SpannableStringBuilder(text), query)
    }

    private fun highlightSpanned(source: CharSequence, query: String): CharSequence {
        val highlighted = SpannableStringBuilder(source)
        if (source is Spanned) {
            source.getSpans(0, source.length, StyleSpan::class.java).forEach { span ->
                val start = source.getSpanStart(span)
                val end = source.getSpanEnd(span)
                if (start >= 0 && end > start) {
                    highlighted.setSpan(
                        ForegroundColorSpan(highlightColor),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    highlighted.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return highlighted

        val plainText = highlighted.toString()
        var searchStart = 0
        while (searchStart < plainText.length) {
            val matchIndex = plainText.indexOf(normalizedQuery, searchStart, ignoreCase = true)
            if (matchIndex < 0) break
            val matchEnd = matchIndex + normalizedQuery.length
            highlighted.setSpan(
                ForegroundColorSpan(highlightColor),
                matchIndex,
                matchEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            highlighted.setSpan(
                StyleSpan(Typeface.BOLD),
                matchIndex,
                matchEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            searchStart = matchEnd
        }
        return highlighted
    }

    private fun contextString(resId: Int): CharSequence {
        return requireNotNull(currentContext).getString(resId)
    }

    class HeaderHolder(
        private val binding: ItemSearchSectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SearchResultUiItem.DateHeader) {
            binding.titleView.text = item.title
        }
    }

    class ResultHolder(
        private val binding: ItemSearchResultRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormatter = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

        fun bind(
            item: SearchResultUiItem.ResultRow,
            isLastResult: Boolean,
            click: ((String) -> Unit)?
        ) {
            binding.titleView.text = item.title
            binding.summaryView.text = item.summary
            binding.dateView.text = dateFormatter.format(Date(item.timestamp))
            binding.summaryView.visibility = if (item.summary.isBlank()) View.GONE else View.VISIBLE
//            binding.dividerView.visibility = if (isLastResult) View.GONE else View.VISIBLE
            binding.root.setOnClickListener { click?.invoke(item.sessionKey) }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_RESULT = 1

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SearchResultUiItem>() {
            override fun areItemsTheSame(
                oldItem: SearchResultUiItem,
                newItem: SearchResultUiItem
            ): Boolean {
                return oldItem.stableId == newItem.stableId
            }

            override fun areContentsTheSame(
                oldItem: SearchResultUiItem,
                newItem: SearchResultUiItem
            ): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        currentContext = recyclerView.context
        highlightColor = ContextCompat.getColor(recyclerView.context, R.color.brand_primary)
    }
}
