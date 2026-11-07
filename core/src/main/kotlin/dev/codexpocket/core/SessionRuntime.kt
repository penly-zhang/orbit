package dev.codexpocket.core

/** A turn stream belongs to its thread even while another tab is visible. */
data class SessionRuntime(val items: List<ChatItem> = emptyList(), val activeTurn: String? = null, val startedAtMs: Long? = null)

fun sessionEvent(s: SessionRuntime, update: TimelineUpdate, nowMs: Long): SessionRuntime {
    fun merge(item: ChatItem, suppliedTurn: String): SessionRuntime {
        val old = s.items.find { it.id == item.id }
        val turnId = suppliedTurn.ifBlank { old?.turnId ?: s.activeTurn.orEmpty() }
        val decorated = item.copy(turnId = turnId, turnStartedAtMs = old?.turnStartedAtMs ?: s.startedAtMs?.takeIf { turnId == s.activeTurn },
            turnCompletedAtMs = old?.turnCompletedAtMs, turnDurationMs = old?.turnDurationMs)
        val items = LinkedHashMap<String, ChatItem>()
        s.items.forEach { items[it.id] = it }; items[decorated.id] = decorated
        return s.copy(items = items.values.toList())
    }
    return when (update) {
        is TimelineUpdate.Started -> s.copy(activeTurn = update.turnId, startedAtMs = update.startedAtMs ?: nowMs)
        is TimelineUpdate.Completed -> {
            val completed = update.completedAtMs ?: nowMs
            val started = s.startedAtMs.takeIf { s.activeTurn == update.turnId } ?: s.items.firstOrNull { it.turnId == update.turnId }?.turnStartedAtMs
            val duration = update.durationMs ?: started?.let { (completed - it).coerceAtLeast(0) }
            s.copy(activeTurn = if (s.activeTurn == update.turnId) null else s.activeTurn, startedAtMs = if (s.activeTurn == update.turnId) null else s.startedAtMs,
                items = s.items.map { if (it.turnId == update.turnId) it.copy(turnCompletedAtMs = completed, turnDurationMs = duration) else it })
        }
        is TimelineUpdate.Item -> merge(update.item, update.turnId)
        is TimelineUpdate.Delta -> {
            val item = s.items.find { it.id == update.itemId } ?: ChatItem(update.itemId, update.kind, "")
            merge(item.copy(text = (item.text + update.text).takeLast(300000)), update.turnId)
        }
        is TimelineUpdate.Paragraph -> s.copy(items = s.items.map { if (it.id == update.itemId && it.text.isNotBlank()) it.copy(text = it.text.trimEnd() + "\n\n") else it })
    }
}
