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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

private val replyDate = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(briefingZone)

@Composable
internal fun ReplySettingsDialog(
    settings: ReplySettings,
    onDismiss: () -> Unit,
    onSave: suspend (ReplySettings) -> Unit,
) {
    var issueText by remember { mutableStateOf(settings.issue.text) }
    var suggestionText by remember { mutableStateOf(settings.suggestion.text) }
    var issueEnabled by remember { mutableStateOf(settings.issue.enabledSince != null) }
    var suggestionEnabled by remember { mutableStateOf(settings.suggestion.enabledSince != null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    BriefingDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("回复设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("自动回复仅发送给开启后收到、网易标记为可回复且未回复的玩家反馈。应用运行时生效。")
                ReplyRuleEditor(ReplyCategory.ISSUE.label, issueText, issueEnabled, !saving, { issueText = it }, { issueEnabled = it })
                HorizontalDivider()
                ReplyRuleEditor(ReplyCategory.SUGGESTION.label, suggestionText, suggestionEnabled, !saving,
                    { suggestionText = it }, { suggestionEnabled = it })
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    saving = true
                    error = ""
                    val now = Instant.now().toEpochMilli()
                    val next = ReplySettings(
                        issue = ReplyRule(issueText.trim(), if (issueEnabled) settings.issue.enabledSince ?: now else null),
                        suggestion = ReplyRule(suggestionText.trim(),
                            if (suggestionEnabled) settings.suggestion.enabledSince ?: now else null),
                    )
                    scope.launch {
                        try {
                            onSave(next)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = "保存回复设置失败，请重试。"
                        } finally {
                            saving = false
                        }
                    }
                },
                shape = controlShape,
                enabled = !saving && issueText.isNotBlank() && suggestionText.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

@Composable
private fun ReplyRuleEditor(
    label: String,
    text: String,
    automatic: Boolean,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onAutomaticChange: (Boolean) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = automatic, onCheckedChange = onAutomaticChange, enabled = enabled)
            Text("自动回复")
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("回复话术") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            enabled = enabled,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickReplyDialog(
    topic: BriefingTopic,
    targets: List<ReplyTarget>,
    settings: ReplySettings,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSend: (List<ReplyRequest>) -> Unit,
) {
    var category by remember { mutableStateOf(ReplyCategory.forTopic(topic.kind)) }
    var text by remember { mutableStateOf(settings.rule(category).text) }
    var selected by remember { mutableStateOf(targets.map(ReplyTarget::key).toSet()) }
    val selectedTargets = targets.filter { it.key in selected }
    BriefingDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("一键回复") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(topic.title, style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplyCategory.entries.forEach { choice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = category == choice,
                                enabled = !busy,
                                onClick = {
                                    category = choice
                                    text = settings.rule(choice).text
                                },
                            )
                            Text(choice.label)
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("回复内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    enabled = !busy,
                )
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 220.dp).padding(top = 4.dp)) {
                    itemsIndexed(targets, key = { _, target -> target.key }) { index, target ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = target.key in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + target.key else selected - target.key
                                },
                                enabled = !busy,
                            )
                            val at = target.publishedAt.takeIf { it > 0 }?.let {
                                " · " + replyDate.format(Instant.ofEpochMilli(it))
                            }.orEmpty()
                            Text(target.source.label + " " + (index + 1) + at)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(selectedTargets.map { ReplyRequest(it, text) }) },
                enabled = !busy && selectedTargets.isNotEmpty() && text.isNotBlank(),
                shape = controlShape,
            ) { Text(if (busy) "发送中…" else "发送（" + selectedTargets.size + " 条）") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
