package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.serialization.Serializable

internal const val NEW_TASK_DRAFT = "new-task-draft"
@Serializable internal data class NewTaskAttachments(val images: List<DraftImage> = emptyList(), val context: List<ContextInput> = emptyList(), val referenceProfile: String? = null, val referenceRoot: String? = null)

// Only local uploads/removal are offered before an execution destination is selected.
internal fun newTaskLocalContext(vm: PocketViewModel, picker: (String, Int) -> Unit) = ContextActions(
    addDocument = { picker(NEW_TASK_DRAFT, 0) }, add = { vm.addContext(NEW_TASK_DRAFT, 0, it) },
    remove = { vm.removeContext(NEW_TASK_DRAFT, 0, it.id) }, files = { _, _ -> emptyList() }, preview = { "" },
    catalog = { error("请先选择电脑和项目") }, plugin = { error("请先选择电脑和项目") },
    enableSkill = {}, reloadMcp = {}, authorize = { error("请先选择电脑和项目") }, finishAuthorization = { _, _ -> },
)
