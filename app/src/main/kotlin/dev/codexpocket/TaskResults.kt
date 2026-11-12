package dev.codexpocket

import dev.codexpocket.core.*
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.Parser

internal data class ResultLink(val label: String, val target: String)
internal fun resultLinks(markdown: String): List<ResultLink> {
    val links = mutableListOf<ResultLink>()
    Parser.builder().build().parse(markdown).accept(object : AbstractVisitor() {
        override fun visit(link: Link) {
            val target = link.destination
            if (isWebLink(target) || isRemoteFileLink(target)) {
                val label = StringBuilder()
                link.accept(object : AbstractVisitor() { override fun visit(text: Text) { label.append(text.literal) } })
                links += ResultLink(label.toString().ifBlank { target }, target)
            }
        }
    })
    return links.distinctBy { it.target }
}

/** Candidates are evidence to inspect, never an inferred test pass. */
internal fun verificationEvidence(items: List<ChatItem>, turnId: String): List<ChatItem> {
    if (turnId.isBlank()) return emptyList()
    val candidate = Regex("(?i)(?:^|[\\s/])(?:pytest|jest|vitest|ctest|unittest|gradle(?:w|\\.sh)?|npm|pnpm|yarn|cargo|go|mvn|mvnw|xcodebuild|flutter|test)(?:[\\s:]|$)")
    val action = Regex("(?i)(?:^|[\\s:])(?:[\\w-]*test[\\w-]*|check|lint[\\w-]*|verify)(?:[\\s:]|$)")
    return items.filter { it.turnId == turnId && it.kind == "commandExecution" &&
        candidate.containsMatchIn(it.command) && action.containsMatchIn(it.command) }
}
