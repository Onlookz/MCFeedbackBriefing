package com.lemon.mcdevmanagermp.briefing

import kotlinx.serialization.Serializable
import java.nio.file.Files

@Serializable
internal enum class BriefingExportFormat(val label: String, val extension: String) {
    XLSX("WPS 表格", "xlsx"), DOCX("WPS 文档", "docx")
}

@Serializable
internal data class ExportProject(val wholeProject: Boolean, val topics: Set<String> = emptySet())

@Serializable
internal data class BriefingExportPlan(
    val format: BriefingExportFormat = BriefingExportFormat.XLSX,
    val projects: Map<String, ExportProject> = emptyMap(),
    val automatic: Boolean = false,
)

internal class BriefingExportSettings(accountId: Long) {
    private val path = briefingDataDirectory.resolve("export-$accountId.json")

    fun load(): BriefingExportPlan? =
        if (Files.notExists(path)) null else briefingJson.decodeFromString(Files.readString(path))

    fun save(plan: BriefingExportPlan) {
        writeBriefingFile(path, briefingJson.encodeToString(plan))
    }
}

internal val BriefingTopic.exportKey: String
    get() = digest(kind.name + "\u0000" + title)

internal fun BriefingSnapshot.selectedBy(plan: BriefingExportPlan): BriefingSnapshot {
    val selection = works.mapNotNull { (id, work) ->
        val project = plan.projects[id] ?: return@mapNotNull null
        if (project.wholeProject) return@mapNotNull id to work
        val selected = work.copy(
            topics = work.topics.filter { it.exportKey in project.topics },
            imageOnlyFeedback = work.imageOnlyFeedback?.takeIf { it.exportKey in project.topics },
        )
        if (selected.allTopics.isEmpty()) null else id to selected
    }.toMap()
    return copy(works = selection)
}
