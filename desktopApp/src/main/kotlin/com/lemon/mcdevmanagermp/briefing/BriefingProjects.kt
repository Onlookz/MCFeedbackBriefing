package com.lemon.mcdevmanagermp.briefing

import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.consts.enums.WorkItemStatusEnum
import com.lemon.mcdevmanagermp.data.repository.ResourceRepositoryImpl
import kotlinx.serialization.Serializable

@Serializable
internal data class BriefingProject(
    val name: String,
    val status: WorkItemStatusEnum = WorkItemStatusEnum.UNKNOWN,
    val weakOffline: Boolean = false,
) {
    val isPublished: Boolean get() = status == WorkItemStatusEnum.ONLINE && !weakOffline

    val statusLabel: String
        get() = when {
            weakOffline && status == WorkItemStatusEnum.ONLINE -> "弱下架"
            weakOffline -> "${status.label} · 弱下架"
            status == WorkItemStatusEnum.UNKNOWN -> "状态待确认"
            else -> status.label
        }
}

internal enum class ProjectFilter(val label: String) {
    PUBLISHED("仅已上架"),
    ALL("全部项目"),
}

internal data class ProjectBriefingRow(
    val key: String,
    val project: BriefingProject,
    val briefing: WorkBriefing?,
) {
    val name: String get() = project.name
    val allTopics: List<BriefingTopic> get() = briefing?.allTopics.orEmpty()
}

internal suspend fun loadBriefingProjects(): Map<String, BriefingProject> {
    val result = ResourceRepositoryImpl.INSTANCE.getResources()
    val data = (result as? NetworkState.Success)?.data
        ?: throw BriefingFailure("网易项目目录暂时无法更新。")
    return buildMap {
        for (item in data.item) {
            if (item.itemId.isNotBlank() && item.itemName.isNotBlank()) {
                put("id:${item.itemId}", BriefingProject(item.itemName.trim(), item.getStatus(), item.weakOffline))
            }
        }
    }
}

internal fun projectBriefings(
    directory: Map<String, BriefingProject>,
    works: Map<String, WorkBriefing>,
    filter: ProjectFilter,
): List<ProjectBriefingRow> {
    val visible = buildList {
        for ((key, project) in directory) {
            if (filter == ProjectFilter.ALL || project.isPublished) {
                add(ProjectBriefingRow(key, project, works[key]))
            }
        }
        if (filter == ProjectFilter.ALL) {
            for ((key, work) in works) {
                if (key !in directory) add(ProjectBriefingRow(key, BriefingProject(work.name), work))
            }
        }
    }
    return visible.sortedWith(compareByDescending<ProjectBriefingRow> { row ->
        row.briefing?.topics.orEmpty().count { it.kind == TopicKind.ISSUE }
    }.thenBy { it.name })
}
