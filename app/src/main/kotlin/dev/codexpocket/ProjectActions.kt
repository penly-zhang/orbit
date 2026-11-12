package dev.codexpocket

import dev.codexpocket.core.*

internal data class ProjectActions(
    val selectHost: (String) -> Unit, val home: () -> Unit, val selectProject: (ProjectInfo) -> Unit,
    val refreshProjects: () -> Unit, val undoArchive: () -> Unit, val dismissArchiveUndo: () -> Unit,
    val showArchived: (Boolean) -> Unit, val newThread: () -> Unit, val load: (Boolean) -> Unit,
    val openThread: (ThreadInfo) -> Unit, val archiveThread: (ThreadInfo) -> Unit,
    val renameThread: (String, String, String) -> Unit,
    val pinThread: (String, String, Boolean) -> Unit,
    val addProject: (suspend (String, String) -> Unit)? = null,
    val projectDirectories: (suspend (String, String) -> List<FileEntry>)? = null,
    val removeProject: ((String, ProjectInfo) -> Unit)? = null,
    val moveProject: ((String, String, Int) -> Unit)? = null,
    val manageThread: ((ProjectInfo, ThreadInfo) -> Unit)? = null,
    val openProjectThread: ((ProjectInfo, ThreadInfo) -> Unit)? = null,
) {
    fun loadThreads(more: Boolean = false) = load(more)
    constructor(vm: PocketViewModel) : this(vm::selectHost, vm::home, vm::selectProject, vm::refreshProjects, vm::undoArchive, vm::dismissArchiveUndo, vm::showArchived, vm::newConversation, vm::loadThreads, { vm.openThread(it) }, vm::archiveThread, vm::renameThread, vm::pinThread, openProjectThread = vm::openProjectThread, addProject = vm::addProject, projectDirectories = vm::projectDirectories, removeProject = vm::removeProject, moveProject = vm::moveProject)
}
