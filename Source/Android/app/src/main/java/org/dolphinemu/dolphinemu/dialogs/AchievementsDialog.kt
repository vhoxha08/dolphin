// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.dialogs

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.databinding.DialogAchievementsBinding
import org.dolphinemu.dolphinemu.features.settings.model.Achievement
import org.dolphinemu.dolphinemu.features.settings.model.AchievementModel
import org.dolphinemu.dolphinemu.features.settings.model.AchievementSubset
import org.dolphinemu.dolphinemu.features.settings.ui.AchievementListItem
import org.dolphinemu.dolphinemu.features.settings.ui.AchievementsAdapter

/**
 * Shows the player's progress through the RetroAchievements set of the running game, as a
 * fullscreen dialog.
 *
 * Only meaningful while [AchievementModel.isGameLoaded] is true. The host is told that the dialog
 * closed through the [REQUEST_CLOSED] fragment result rather than by being called directly, so
 * that any activity can show this.
 */
class AchievementsDialog : DialogFragment() {
    private var _binding: DialogAchievementsBinding? = null
    private val binding get() = _binding!!

    private lateinit var achievements: Array<Achievement>
    private lateinit var subsets: List<AchievementSubset>
    private lateinit var adapter: AchievementsAdapter

    private var selectedSubsetId = NO_SUBSET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.FullscreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAchievementsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setInsets()

        achievements = AchievementModel.getAchievementList()
        subsets = AchievementModel.getSubsetList().toList()

        selectedSubsetId = savedInstanceState?.getInt(KEY_SELECTED_SUBSET)
            ?.takeIf { id -> subsets.any { it.id == id } }
            ?: subsets.firstOrNull()?.id
            ?: NO_SUBSET

        adapter = AchievementsAdapter(buildItems()) { subsetId ->
            if (subsetId != selectedSubsetId) {
                selectedSubsetId = subsetId
                adapter.submitItems(buildItems())
            }
        }

        binding.apply {
            buttonClose.setOnClickListener { dismiss() }

            listAchievements.layoutManager = LinearLayoutManager(requireContext())
            listAchievements.adapter = this@AchievementsDialog.adapter
        }
    }

    /**
     * The summary and the subset filter ride along as list items so that they scroll away with the
     * achievements instead of eating the top of the dialog.
     */
    private fun buildItems(): List<AchievementListItem> {
        val items = ArrayList<AchievementListItem>()
        val selected = subsets.firstOrNull { it.id == selectedSubsetId }

        items.add(
            AchievementListItem.Summary(
                title = selected?.title ?: AchievementModel.getGameDisplayName(),
                badgeUrl = selected?.badgeUrl.orEmpty(),
                summary = if (selected != null) {
                    AchievementModel.getSubsetProgress(selected.id)
                } else {
                    AchievementModel.getGameProgress()
                }
            )
        )

        // A single subset has nothing to filter by, and its achievements report a subset id of
        // zero rather than the subset's own id, so filtering them would empty the list.
        if (subsets.size > 1) {
            items.add(AchievementListItem.SubsetFilter(subsets, selectedSubsetId))
            items.addAll(
                AchievementListItem.fromAchievements(
                    achievements.filter { it.subsetId == selectedSubsetId },
                    selected?.title
                )
            )
        } else {
            items.addAll(AchievementListItem.fromAchievements(achievements.asList()))
        }

        return items
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_SUBSET, selectedSubsetId)
    }

    // The dialog covers the whole screen, including the areas the emulation activity draws behind.
    private fun setInsets() {
        val root = binding.layoutRoot
        ViewCompat.setOnApplyWindowInsetsListener(root) { v: View, windowInsets: WindowInsetsCompat ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        if (activity?.isChangingConfigurations == true) {
            return
        }

        parentFragmentManager.setFragmentResult(REQUEST_CLOSED, Bundle.EMPTY)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AchievementsDialog"

        /** Sent when the dialog goes away for good, so the host can resume what it paused. */
        const val REQUEST_CLOSED = "AchievementsDialog.closed"

        private const val KEY_SELECTED_SUBSET = "selected_subset"
        private const val NO_SUBSET = 0
    }
}
