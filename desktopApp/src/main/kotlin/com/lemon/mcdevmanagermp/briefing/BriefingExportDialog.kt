package com.lemon.mcdevmanagermp.briefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BriefingExportDialog(
    snapshot: BriefingSnapshot,
    initial: BriefingExportPlan?,
    onDismiss: () -> Unit,
    onSave: suspend (BriefingExportPlan, exportNow: Boolean) -> Unit,
) {
    val works = snapshot.works.entries.filter { it.value.allTopics.isNotEmpty() }.sortedBy { it.value.name }
    var format by remember { mutableStateOf(initial?.format ?: BriefingExportFormat.XLSX) }
    var projects by remember {
        mutableStateOf(initial?.projects ?: works.associate { it.key to ExportProject(wholeProject = true) })
    }
    var automatic by remember { mutableStateOf(initial?.automatic ?: false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun selected(id: String, topic: BriefingTopic): Boolean =
        projects[id]?.let { it.wholeProject || topic.exportKey in it.topics } == true

    val selectedCount = works.sumOf { work -> work.value.allTopics.count { selected(work.key, it) } }
    fun submit(now: Boolean) {
        saving = true
        error = ""
        val plan = BriefingExportPlan(format, projects, automatic)
        scope.launch {
            try {
                onSave(plan, now)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "保存导出设置或文件失败，请重试。"
            } finally {
                saving = false
            }
        }
    }

    BriefingDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("导出简报") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalBriefingAppearance.current.spacing.gap.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BriefingExportFormat.entries.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = format == option, enabled = !saving, onClick = { format = option })
                            Text(option.label)
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 $selectedCount 条", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        onClick = { projects = works.associate { it.key to ExportProject(wholeProject = true) } },
                        enabled = !saving && works.isNotEmpty(),
                    ) { Text("全选") }
                    TextButton(
                        onClick = { projects = emptyMap() },
                        enabled = !saving && projects.isNotEmpty(),
                    ) { Text("清空") }
                }
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 460.dp)) {
                    works.forEach { (id, work) ->
                        val all = work.allTopics
                        val checkedCount = all.count { selected(id, it) }
                        item(key = id) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TriStateCheckbox(
                                    state = when (checkedCount) {
                                        0 -> ToggleableState.Off
                                        all.size -> ToggleableState.On
                                        else -> ToggleableState.Indeterminate
                                    },
                                    enabled = !saving,
                                    onClick = {
                                        projects = if (checkedCount == all.size) projects - id
                                            else projects + (id to ExportProject(wholeProject = true))
                                    },
                                )
                                Text(work.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(
                                    enabled = !saving,
                                    onClick = { expanded = id.takeUnless { it == expanded } },
                                ) { Text(if (expanded == id) "收起" else "选条目") }
                            }
                        }
                        if (expanded == id) {
                            all.forEach { topic ->
                                item(key = id + ":" + topic.exportKey) {
                                    Row(
                                        modifier = Modifier.padding(start = 22.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = selected(id, topic),
                                            enabled = !saving,
                                            onCheckedChange = { checked ->
                                                val current = all.filter { selected(id, it) }.map(BriefingTopic::exportKey).toSet()
                                                val next = if (checked) current + topic.exportKey else current - topic.exportKey
                                                projects = if (next.isEmpty()) projects - id
                                                else projects + (id to ExportProject(wholeProject = false, topics = next))
                                            },
                                        )
                                        Text(topic.kind.label + " · " + topic.title, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = automatic,
                        enabled = !saving,
                        onCheckedChange = { automatic = it },
                    )
                    Text("整理后按此范围自动导出")
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = { submit(true) },
                enabled = !saving && selectedCount > 0,
                shape = controlShape,
            ) { Text(if (saving) "处理中…" else "导出（$selectedCount 条）") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                TextButton(
                    onClick = { submit(false) },
                    enabled = !saving && (!automatic || projects.isNotEmpty()),
                ) { Text("保存设置") }
            }
        },
    )
}
