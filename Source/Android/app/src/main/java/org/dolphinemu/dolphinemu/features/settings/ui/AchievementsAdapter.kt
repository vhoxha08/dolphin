// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.settings.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import coil.load
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.dolphinemu.dolphinemu.R
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.dolphinemu.dolphinemu.databinding.ListItemAchievementBinding
import org.dolphinemu.dolphinemu.databinding.ListItemAchievementHeaderBinding
import org.dolphinemu.dolphinemu.databinding.ListItemAchievementsSummaryBinding
import org.dolphinemu.dolphinemu.databinding.ListItemSubsetFilterBinding
import org.dolphinemu.dolphinemu.features.settings.model.Achievement
import org.dolphinemu.dolphinemu.features.settings.model.AchievementModel
import org.dolphinemu.dolphinemu.features.settings.model.AchievementSubset

/**
 * One row of the achievement list: either a group header or an achievement.
 */
sealed class AchievementListItem {
    data class Summary(
        val title: String,
        val badgeUrl: String,
        val summary: AchievementModel.GameProgress
    ) : AchievementListItem()

    data class SubsetFilter(
        val subsets: List<AchievementSubset>,
        val selectedSubsetId: Int
    ) : AchievementListItem()

    data class Header(val label: String) : AchievementListItem()
    data class Entry(val achievement: Achievement) : AchievementListItem()

    companion object {
        const val TYPE_SUMMARY = 0
        const val TYPE_SUBSET_FILTER = 1
        const val TYPE_HEADER = 2
        const val TYPE_ACHIEVEMENT = 3

        /**
         * Turns the flat list coming from native code back into groups. The achievements are
         * already ordered by bucket, so a header is emitted whenever the group changes.
         *
         * The subset is part of the group because rcheevos only prefixes bucket labels with the
         * subset title for some bucket types; two subsets can both produce an "Almost There",
         * and those must not be merged into one group.
         */
        fun fromAchievements(
            achievements: List<Achievement>,
            subsetTitle: String? = null
        ): List<AchievementListItem> {
            // rcheevos prefixes bucket labels with the subset title when a game has more than one
            // subset ("Bonus - Locked"). The header above the list already names the subset, so
            // the group text drops it again.
            val prefix = subsetTitle?.takeIf { it.isNotEmpty() }?.let { "$it - " }

            val items = ArrayList<AchievementListItem>(achievements.size)
            var currentGroup: Pair<Int, String>? = null

            for (achievement in achievements) {
                val group = achievement.subsetId to achievement.bucketLabel
                if (group != currentGroup) {
                    currentGroup = group

                    val label = if (prefix == null) {
                        achievement.bucketLabel
                    } else {
                        achievement.bucketLabel.removePrefix(prefix)
                    }
                    if (label.isNotEmpty()) {
                        items.add(Header(label))
                    }
                }
                items.add(Entry(achievement))
            }

            return items
        }
    }
}

class AchievementsAdapter(
    items: List<AchievementListItem>,
    private val onSubsetSelected: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<AchievementListItem> = items

    /** The list is small enough that rebuilding it wholesale on a filter change is fine. */
    @SuppressLint("NotifyDataSetChanged")
    fun submitItems(newItems: List<AchievementListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            AchievementListItem.TYPE_SUMMARY ->
                SummaryViewHolder(
                    ListItemAchievementsSummaryBinding.inflate(inflater, parent, false)
                )

            AchievementListItem.TYPE_SUBSET_FILTER ->
                SubsetFilterViewHolder(
                    ListItemSubsetFilterBinding.inflate(inflater, parent, false),
                    onSubsetSelected
                )

            AchievementListItem.TYPE_HEADER ->
                HeaderViewHolder(ListItemAchievementHeaderBinding.inflate(inflater, parent, false))

            AchievementListItem.TYPE_ACHIEVEMENT ->
                AchievementViewHolder(ListItemAchievementBinding.inflate(inflater, parent, false))

            else -> throw UnsupportedOperationException()
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is AchievementListItem.Summary -> (holder as SummaryViewHolder).bind(item)
            is AchievementListItem.SubsetFilter -> (holder as SubsetFilterViewHolder).bind(item)
            is AchievementListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is AchievementListItem.Entry -> (holder as AchievementViewHolder).bind(item.achievement)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is AchievementListItem.Summary -> AchievementListItem.TYPE_SUMMARY
        is AchievementListItem.SubsetFilter -> AchievementListItem.TYPE_SUBSET_FILTER
        is AchievementListItem.Header -> AchievementListItem.TYPE_HEADER
        is AchievementListItem.Entry -> AchievementListItem.TYPE_ACHIEVEMENT
    }

    private class SummaryViewHolder(private val binding: ListItemAchievementsSummaryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AchievementListItem.Summary) {
            binding.apply {
                val context = root.context
                val summary = item.summary

                textGameName.text = item.title

                if (item.badgeUrl.isEmpty()) {
                    imageGameBadge.setImageDrawable(null)
                } else {
                    imageGameBadge.load(item.badgeUrl)
                }

                // A range of 0 would render an empty set as complete instead of empty.
                progressAchievements.max =
                    if (summary.totalAchievements == 0) 1 else summary.totalAchievements
                progressAchievements.progress = summary.unlockedAchievements

                textAchievements.text = context.getString(
                    R.string.achievements_unlocked_count,
                    summary.unlockedAchievements,
                    summary.totalAchievements
                )
                textPoints.text = context.getString(
                    R.string.achievements_points_earned,
                    summary.unlockedPoints,
                    summary.totalPoints
                )
            }
        }
    }

    private class SubsetFilterViewHolder(
        private val binding: ListItemSubsetFilterBinding,
        private val onSubsetSelected: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(filter: AchievementListItem.SubsetFilter) {
            val group = binding.groupSubsets

            // Rebuilt rather than rebound: the subsets are only known at runtime, and the chips
            // have to be recreated anyway when this holder is recycled onto another game.
            group.setOnCheckedStateChangeListener(null)
            group.removeAllViews()

            for (subset in filter.subsets) {
                addChip(group, subset.id, subset.title, filter.selectedSubsetId)
            }

            group.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
                val selected = checkedIds.firstOrNull()
                    ?.let { chipGroup.findViewById<Chip>(it) }
                    ?.tag as? Int
                selected?.let(onSubsetSelected)
            }
        }

        private fun addChip(group: ChipGroup, subsetId: Int, title: String, selectedId: Int) {
            val chip = LayoutInflater.from(group.context)
                .inflate(R.layout.list_item_subset_chip, group, false) as Chip

            // The subset id rides along as the tag; view ids are generated so that a subset id of
            // zero cannot collide with View.NO_ID.
            chip.id = View.generateViewId()
            chip.tag = subsetId
            chip.text = title
            chip.isChecked = subsetId == selectedId

            group.addView(chip)
        }
    }

    private class HeaderViewHolder(private val binding: ListItemAchievementHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(header: AchievementListItem.Header) {
            binding.textHeaderName.text = header.label
        }
    }

    private class AchievementViewHolder(private val binding: ListItemAchievementBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /**
         * Shows the achievement type. The types are mutually exclusive, so a single view is
         * retinted rather than one view per type.
         */
        private fun bindBadges(achievement: Achievement) {
            val context: Context = binding.root.context

            val typeBadge = when {
                achievement.isMissable ->
                    R.string.achievements_type_missable to R.color.cheevo_badge_missable

                achievement.isProgression ->
                    R.string.achievements_type_progression to R.color.cheevo_badge_progression

                achievement.isWin ->
                    R.string.achievements_type_win to R.color.cheevo_badge_win

                else -> null
            }

            binding.textBadgeType.apply {
                if (typeBadge == null) {
                    visibility = View.GONE
                } else {
                    val (label, color) = typeBadge
                    setText(label)
                    backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(context, color))
                    visibility = View.VISIBLE
                }
            }

            binding.layoutBadges.visibility = binding.textBadgeType.visibility
        }

        /**
         * Rings the badge in gold when the achievement was earned in hardcore mode, the same cue
         * DolphinQt uses.
         */
        private fun bindBadgeRing(achievement: Achievement) {
            val context: Context = binding.root.context

            binding.imageBadge.apply {
                if (achievement.isHardcoreUnlocked) {
                    strokeColor = ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.cheevo_hardcore_ring)
                    )
                    contentDescription = context.getString(R.string.achievements_unlocked_hardcore)
                } else {
                    strokeColor = null
                    contentDescription = null
                }
            }
        }

        fun bind(achievement: Achievement) {
            binding.apply {
                textTitle.text = achievement.title
                textDescription.text = achievement.description
                textPoints.text = root.context.getString(
                    R.string.achievements_points_value,
                    achievement.points
                )

                // Only measured achievements report progress, and an unlocked one is simply done.
                val showProgress =
                    !achievement.isUnlocked && achievement.measuredProgress.isNotEmpty()
                textMeasuredProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
                if (showProgress) {
                    textMeasuredProgress.text = achievement.measuredProgress
                }

                imageBadge.setImageBitmap(achievement.badge)

                bindBadgeRing(achievement)
                bindBadges(achievement)

                // Dim the ones that are still locked instead of hiding them.
                layoutAchievement.alpha = if (achievement.isUnlocked) 1.0f else 0.5f
            }
        }
    }
}
