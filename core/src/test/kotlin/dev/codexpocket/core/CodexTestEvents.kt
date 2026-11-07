package dev.codexpocket.core

import kotlinx.serialization.json.JsonObject

/** Convenience entrypoint for protocol fixture tests. Runtime itself accepts only normalized updates. */
fun sessionEvent(s: SessionRuntime, method: String, params: JsonObject, nowMs: Long): SessionRuntime =
    codexTimelineUpdate(method, params)?.let { sessionEvent(s, it, nowMs) } ?: s
