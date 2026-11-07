package dev.codexpocket

import androidx.compose.runtime.Composable
import dev.codexpocket.core.*

internal data class ReviewActions(
    val chooseReviewRange: (String) -> Unit, val refreshChanges: () -> Unit, val refreshDiff: () -> Unit,
    val openDiff: (GitChange, String) -> Unit, val toggleReviewViewed: (String, String) -> Unit,
    val navigateReviewFile: (Int) -> Unit, val showReviewComments: (Boolean) -> Unit,
    val relocateReviewComment: (String) -> Unit, val cancelReviewRelocation: () -> Unit,
    val reanchorReviewComment: (String, DiffLine?, String) -> Unit,
    val addReviewComment: (DiffLine?, String) -> Unit, val editReviewComment: (String, String) -> Unit,
    val removeReviewComment: (String) -> Unit, val sendReviewComments: (Set<String>?, Boolean, Boolean) -> Unit,
)

private fun reviewActions(vm: PocketViewModel) = ReviewActions(
    vm::chooseReviewRange, vm::refreshChanges, vm::refreshDiff, vm::openDiff, vm::toggleReviewViewed,
    vm::navigateReviewFile, vm::showReviewComments, vm::relocateReviewComment, vm::cancelReviewRelocation,
    vm::reanchorReviewComment, vm::addReviewComment, vm::editReviewComment, vm::removeReviewComment, vm::sendReviewComments,
)

@Composable internal fun ReviewChangesScreen(s: PocketState, vm: PocketViewModel, runReview: () -> Unit) = ReviewChangesScreen(s, reviewActions(vm), runReview)
@Composable internal fun ReviewDiffScreen(s: PocketState, vm: PocketViewModel) = ReviewDiffScreen(s, reviewActions(vm))
