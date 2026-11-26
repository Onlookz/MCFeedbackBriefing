package com.lemon.mcdevmanagermp.briefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.format.DateTimeFormatter

private val originalTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(briefingZone)

@Composable
internal fun FeedbackOriginalsDialog(
    title: String,
    canConfirm: Boolean,
    onDismiss: () -> Unit,
    onExpired: () -> Unit,
    load: suspend () -> BriefingOriginals,
) {
    var confirmed by remember { mutableStateOf(false) }
    if (confirmed) {
        ConfirmedOriginalsDialog(title, onDismiss, onExpired, load)
        return
    }

    val cancelFocus = remember { FocusRequester() }
    BriefingDialog(
        onDismissRequest = onDismiss,
        title = { Text("确定查看原文？") },
        text = {
            Text(
                if (canConfirm) "原文按玩家提交的内容显示，可能有刺耳的措辞。准备好后再查看。"
                else "正在同步反馈，完成后即可选择是否查看原文。",
            )
        },
        confirmButton = {
            OutlinedButton(
                onClick = { confirmed = true },
                shape = controlShape,
                enabled = canConfirm,
            ) { Text("确认查看") }
        },
        dismissButton = {
            Button(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus), shape = controlShape) {
                Text("取消")
            }
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        },
    )
}

@Composable
private fun ConfirmedOriginalsDialog(
    title: String,
    onDismiss: () -> Unit,
    onExpired: () -> Unit,
    load: suspend () -> BriefingOriginals,
) {
    var result by remember { mutableStateOf<BriefingOriginals?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            result = load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: BriefingSessionExpired) {
            onExpired()
        } catch (e: BriefingFailure) {
            error = e.message
        } catch (_: Exception) {
            error = "读取原文失败，请稍后重试。"
        }
    }

    val originals = result
    val entry = originals?.entries?.getOrNull(position)
    val total = originals?.entries?.size ?: 0
    val missing = originals?.missing ?: 0
    BriefingDialog(
        onDismissRequest = onDismiss,
        title = { Text("反馈原文") },
        text = {
            key(position) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val colors = MaterialTheme.colorScheme
                    Text(title, color = colors.onSurfaceVariant)
                    val failure = error
                    when {
                        failure != null -> Text(failure)
                        originals == null -> Text("正在读取原文…")
                        entry == null -> Text("暂未找到这张卡片关联的原文。")
                        else -> {
                            val time = entry.publishedAt.takeIf { it > 0 }?.let {
                                originalTimeFormat.format(Instant.ofEpochMilli(it))
                            } ?: "时间未知"
                            Text(
                                "${position + 1} / $total · ${entry.source.label} · $time",
                                color = colors.onSurfaceVariant,
                            )
                            HorizontalDivider()
                            if (entry.text.isBlank()) {
                                Text("这条反馈没有文字，可由卡片查看截图。")
                            } else {
                                SelectionContainer {
                                    Text(entry.text, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    if (entry != null && missing > 0) {
                        Text("还有 $missing 条原文暂未找到。", color = colors.onSurfaceVariant)
                    }
                    originals?.warnings?.forEach { Text(it, color = colors.onSurfaceVariant) }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss, shape = controlShape) { Text("关闭") } },
        dismissButton = {
            if (total > 1) {
                OutlinedButton(onClick = { position-- }, enabled = position > 0, shape = controlShape) {
                    Text("上一条")
                }
                OutlinedButton(onClick = { position++ }, enabled = position < total - 1, shape = controlShape) {
                    Text("下一条")
                }
            }
        },
    )
}
