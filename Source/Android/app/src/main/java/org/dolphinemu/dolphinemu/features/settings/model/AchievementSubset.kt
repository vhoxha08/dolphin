// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.settings.model

import androidx.annotation.Keep

/**
 * A set of achievements attached to the running game. Games normally have a single subset, but
 * some have bonus ones on top of it.
 *
 * Constructed from native code, so the constructor signature is mirrored in IDCache.cpp.
 */
@Keep
data class AchievementSubset(
    val id: Int,
    val title: String,
    /** Where the subset's icon can be fetched from. Empty if the server did not supply one. */
    val badgeUrl: String
)
