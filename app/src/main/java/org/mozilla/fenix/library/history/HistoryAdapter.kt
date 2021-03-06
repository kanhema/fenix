/* This Source Code Form is subject to the terms of the Mozilla Public
   License, v. 2.0. If a copy of the MPL was not distributed with this
   file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.library.history

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.Observer
import org.mozilla.fenix.R
import kotlinx.android.synthetic.main.history_header.view.*
import kotlinx.android.synthetic.main.history_list_item.view.*
import mozilla.components.browser.menu.BrowserMenu
import org.mozilla.fenix.components.SectionedAdapter
import java.lang.IllegalStateException
import java.util.Date
import java.util.Calendar

private class HistoryList(val history: List<HistoryItem>) {
    enum class Range {
        Today, ThisWeek, ThisMonth, Older;

        fun humanReadable(context: Context): String = when (this) {
            Today -> context.getString(R.string.history_today)
            ThisWeek -> context.getString(R.string.history_this_week)
            ThisMonth -> context.getString(R.string.history_this_month)
            Older -> context.getString(R.string.history_older)
        }
    }

    val ranges: List<Range>
        get() = grouped.keys.toList()

    fun itemsInRange(range: Range): List<HistoryItem> {
        return grouped[range] ?: listOf()
    }

    fun item(range: Range, index: Int): HistoryItem? = grouped[range]?.let { it[index] }

    private val grouped: Map<Range, List<HistoryItem>>

    init {
        val oneDayAgo = getDaysAgo(zero_days).time
        val sevenDaysAgo = getDaysAgo(seven_days).time
        val thirtyDaysAgo = getDaysAgo(thirty_days).time

        val lastWeek = LongRange(sevenDaysAgo, oneDayAgo)
        val lastMonth = LongRange(thirtyDaysAgo, sevenDaysAgo)

        grouped = history.groupBy { item ->
            when {
                DateUtils.isToday(item.visitedAt) -> Range.Today
                lastWeek.contains(item.visitedAt) -> Range.ThisWeek
                lastMonth.contains(item.visitedAt) -> Range.ThisMonth
                else -> Range.Older
            }
        }
    }

    private fun getDaysAgo(daysAgo: Int): Date {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)

        return calendar.time
    }

    companion object {
        private const val zero_days = 0
        private const val seven_days = 7
        private const val thirty_days = 30
    }
}

class HistoryAdapter(
    private val actionEmitter: Observer<HistoryAction>
) : SectionedAdapter() {
    override fun numberOfSections(): Int = historyList.ranges.size

    override fun numberOfRowsInSection(section: Int): Int = historyList.itemsInRange(historyList.ranges[section]).size

    override fun onCreateHeaderViewHolder(parent: ViewGroup): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(HistoryHeaderViewHolder.LAYOUT_ID, parent, false)
        return HistoryHeaderViewHolder(view)
    }

    override fun onBindHeaderViewHolder(holder: RecyclerView.ViewHolder, header: SectionType.Header) {
        val sectionTitle = historyList.ranges[header.index].humanReadable(holder.itemView.context)

        when (holder) {
            is HistoryHeaderViewHolder -> holder.bind(sectionTitle)
        }
    }

    override fun onCreateItemViewHolder(parent: ViewGroup): RecyclerView.ViewHolder {
        val view = LayoutInflater
            .from(parent.context)
            .inflate(HistoryListItemViewHolder.LAYOUT_ID, parent, false)
        return HistoryListItemViewHolder(view, actionEmitter)
    }

    override fun onBindItemViewHolder(holder: RecyclerView.ViewHolder, row: SectionType.Row) {
        val item = historyList.ranges[row.section]
            .let { historyList.item(it, row.row) } ?: throw IllegalStateException("No item for row: $row")

        (holder as? HistoryListItemViewHolder)?.bind(item, mode)
    }

    class HistoryListItemViewHolder(
        view: View,
        private val actionEmitter: Observer<HistoryAction>
    ) : RecyclerView.ViewHolder(view) {

        private val checkbox = view.should_remove_checkbox
        private val favicon = view.history_favicon
        private val title = view.history_title
        private val url = view.history_url
        private val menuButton = view.history_item_overflow

        private var item: HistoryItem? = null
        private lateinit var historyMenu: HistoryItemMenu
        private var mode: HistoryState.Mode = HistoryState.Mode.Normal
        private val checkListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (mode is HistoryState.Mode.Normal) {
                return@OnCheckedChangeListener
            }

            item?.apply {
                val action = if (isChecked) {
                    HistoryAction.AddItemForRemoval(this)
                } else {
                    HistoryAction.RemoveItemForRemoval(this)
                }

                actionEmitter.onNext(action)
            }
        }

        init {
            setupMenu()

            view.setOnClickListener {
                if (mode is HistoryState.Mode.Editing) {
                    checkbox.isChecked = !checkbox.isChecked
                    return@setOnClickListener
                }

                item?.apply {
                    actionEmitter.onNext(HistoryAction.Select(this))
                }
            }

            view.setOnLongClickListener {
                item?.apply {
                    actionEmitter.onNext(HistoryAction.EnterEditMode(this))
                }

                true
            }

            menuButton.setOnClickListener {
                historyMenu.menuBuilder.build(view.context).show(
                    anchor = it,
                    orientation = BrowserMenu.Orientation.DOWN)
            }

            checkbox.setOnCheckedChangeListener(checkListener)
        }

        fun bind(item: HistoryItem, mode: HistoryState.Mode) {
            this.item = item
            this.mode = mode

            title.text = item.title
            url.text = item.url

            val isEditing = mode is HistoryState.Mode.Editing
            checkbox.visibility = if (isEditing) { View.VISIBLE } else { View.GONE }
            favicon.visibility = if (isEditing) { View.INVISIBLE } else { View.VISIBLE }

            if (mode is HistoryState.Mode.Editing) {
                checkbox.setOnCheckedChangeListener(null)

                // Don't set the checkbox if it already contains the right value.
                // This prevent us from cutting off the animation
                val shouldCheck = mode.selectedItems.contains(item)
                if (checkbox.isChecked != shouldCheck) {
                    checkbox.isChecked = mode.selectedItems.contains(item)
                }
                checkbox.setOnCheckedChangeListener(checkListener)
            }
        }

        private fun setupMenu() {
            this.historyMenu = HistoryItemMenu(itemView.context) {
                when (it) {
                    is HistoryItemMenu.Item.Delete -> {
                        item?.apply { actionEmitter.onNext(HistoryAction.Delete.One(this)) }
                    }
                }
            }
        }

        companion object {
            const val LAYOUT_ID = R.layout.history_list_item
        }
    }

    class HistoryHeaderViewHolder(
        view: View
    ) : RecyclerView.ViewHolder(view) {
        private val title = view.history_header_title

        fun bind(title: String) {
            this.title.text = title
        }

        companion object {
            const val LAYOUT_ID = R.layout.history_header
        }
    }

    private var historyList: HistoryList = HistoryList(emptyList())
    private var mode: HistoryState.Mode = HistoryState.Mode.Normal

    fun updateData(items: List<HistoryItem>, mode: HistoryState.Mode) {
        this.historyList = HistoryList(items)
        this.mode = mode
        notifyDataSetChanged()
    }
}
