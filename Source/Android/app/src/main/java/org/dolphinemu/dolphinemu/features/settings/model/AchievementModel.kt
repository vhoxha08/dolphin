// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.settings.model

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap

object AchievementModel {
    @JvmStatic
    external fun init()

    suspend fun asyncLogin(password: String): Boolean {
        return withContext(Dispatchers.IO) {
            login(password)
        }
    }

    @JvmStatic
    private external fun login(password: String): Boolean

    @JvmStatic
    external fun logout()

    @JvmStatic
    external fun isHardcoreModeActive(): Boolean

    @JvmStatic
    external fun isGameLoaded(): Boolean

    @JvmStatic
    external fun getGameDisplayName(): String

    /**
     * The player's progress through the achievement set of the running game. All values are zero if
     * no achievement set is loaded.
     */
    data class GameProgress(
        val unlockedAchievements: Int,
        val totalAchievements: Int,
        val unlockedPoints: Int,
        val totalPoints: Int
    )

    fun getGameProgress(): GameProgress = toGameProgress(getProgressValues())

    fun getSubsetProgress(subsetId: Int): GameProgress =
        toGameProgress(getSubsetProgressValues(subsetId))

    private fun toGameProgress(values: IntArray) =
        GameProgress(values[0], values[1], values[2], values[3])

    @JvmStatic
    private external fun getProgressValues(): IntArray

    @JvmStatic
    private external fun getSubsetProgressValues(subsetId: Int): IntArray

    /**
     * The achievements of the running game, ordered by the group each one belongs to (see
     * [Achievement.bucketLabel]). Empty if no achievement set is loaded.
     */
    @JvmStatic
    external fun getAchievementList(): Array<Achievement>

    /**
     * The subsets of the running game, the first being the base set. Empty if no achievement set
     * is loaded.
     */
    @JvmStatic
    external fun getSubsetList(): Array<AchievementSubset>

    /**
     * The badge of an achievement, greyed out when [locked]. Badges are downloaded in the
     * background when a game is loaded, so this is null until the download has finished.
     */
    fun getAchievementBadge(id: Int, locked: Boolean): Bitmap? {
        val values = getAchievementBadgeValues(id, locked)
        if (values.size <= BADGE_HEADER_SIZE) {
            return null
        }

        val width = values[0]
        val height = values[1]
        if (width <= 0 || height <= 0) {
            return null
        }

        return createBitmap(width, height).apply {
            setPixels(values, BADGE_HEADER_SIZE, width, 0, 0, width, height)
        }
    }

    /** Laid out as {width, height, pixels...}, see the native side. */
    @JvmStatic
    private external fun getAchievementBadgeValues(id: Int, locked: Boolean): IntArray

    private const val BADGE_HEADER_SIZE = 2

    @JvmStatic
    external fun shutdown()
}
