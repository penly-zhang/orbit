package dev.codexpocket

import dev.codexpocket.core.ThreadInfo
import kotlinx.serialization.Serializable

/** Only first pages are cached, so a saved cursor always follows the displayed records. */
@Serializable internal data class SavedThreadList(
    val projectId: String, val archived: Boolean, val threads: List<ThreadInfo>, val cursor: String? = null,
)
