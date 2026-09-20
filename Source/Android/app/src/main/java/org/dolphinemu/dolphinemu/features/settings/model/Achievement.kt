// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.settings.model

import android.graphics.Bitmap
import androidx.annotation.Keep

/**
 * A single RetroAchievements achievement of the running game.
 *
 * Constructed from native code, so the constructor signature is mirrored in IDCache.cpp.
 */
@Keep
data class Achievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val state: Int,
    val measuredProgress: String,
    /** Label of the group this achievement was sorted into, e.g. "Locked" or "Almost There". */
    val bucketLabel: String,
    val type: Int,
    val unlocked: Int,
    val subsetId: Int
) {
    /**
     * The badge image, in colour once the achievement is unlocked and greyed out while it is not.
     * Null if the badge has not been downloaded yet.
     *
     * Loaded on first use rather than passed through the constructor, so that opening the dialog
     * does not copy the pixels of every achievement of the game across the JNI boundary at once.
     */
    val badge: Bitmap? by lazy { AchievementModel.getAchievementBadge(id, !isUnlocked) }

    val isUnlocked: Boolean
        get() = state == STATE_UNLOCKED

    // unlocked is a bitmask: rcheevos stores a hardcore unlock as UNLOCK_BOTH, never as
    // UNLOCK_HARDCORE on its own, so this has to be a bit test rather than a comparison.
    val isHardcoreUnlocked: Boolean
        get() = (unlocked and UNLOCK_HARDCORE) != 0

    val isMissable: Boolean
        get() = type == TYPE_MISSABLE

    val isProgression: Boolean
        get() = type == TYPE_PROGRESSION

    val isWin: Boolean
        get() = type == TYPE_WIN

    val isLocked: Boolean
        get() = unlocked == UNLOCK_NONE

    companion object {
        const val STATE_INACTIVE = 0
        const val STATE_ACTIVE = 1
        const val STATE_UNLOCKED = 2
        const val STATE_DISABLED = 3

        const val TYPE_STANDARD = 0
        const val TYPE_MISSABLE = 1
        const val TYPE_PROGRESSION = 2
        const val TYPE_WIN = 3

        const val UNLOCK_NONE = 0
        const val UNLOCK_SOFTCORE = 1
        const val UNLOCK_HARDCORE = 2
    }
}
