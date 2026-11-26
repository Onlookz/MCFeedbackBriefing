package com.lemon.mcdevmanagermp.briefing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BriefingDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    val display = LocalBriefingAppearance.current
    val viewport = LocalBriefingViewport.current
    val density = LocalDensity.current
    val width = minOf(display.dialogWidth.dp, (viewport.width - 32.dp).coerceAtLeast(260.dp))
    val maxHeight = (viewport.height - 32.dp).coerceAtLeast(230.dp)
    val padding = (display.spacing.pagePadding + 7).dp
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
            Surface(
                modifier = Modifier.width(width).heightIn(max = maxHeight),
                shape = controlShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(display.spacing.gap.dp),
                ) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)) {
                        title()
                    }
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BriefingAppearanceDialog(
    draft: BriefingAppearance,
    onChange: (BriefingAppearance) -> Unit,
    onDismiss: () -> Unit,
    saving: Boolean,
    error: String,
    onSave: () -> Unit,
) {
    var hex by remember { mutableStateOf(briefingColorText(draft.background)) }
    val validColor = parseBriefingColor(hex)
    BriefingDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("显示设置") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DisplaySlider("字体大小", draft.textPercent, 100..160, step = 5, unit = "%", enabled = !saving) {
                    onChange(draft.copy(textPercent = it))
                }
                HorizontalDivider()
                Text("项目栏位置", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectPlacement.entries.forEach { placement ->
                        DisplayChoice(placement.label, draft.placement == placement, !saving) {
                            onChange(draft.copy(placement = placement))
                        }
                    }
                }
                DisplaySlider(
                    "侧栏宽度", draft.sidebarWidth, 190..410, step = 10,
                    enabled = !saving && draft.placement != ProjectPlacement.TOP,
                ) { onChange(draft.copy(sidebarWidth = it)) }
                DisplaySlider("弹窗宽度", draft.dialogWidth, 520..920, step = 20, enabled = !saving) {
                    onChange(draft.copy(dialogWidth = it))
                }
                Text("内容间距", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BriefingSpacing.entries.forEach { spacing ->
                        DisplayChoice(spacing.label, draft.spacing == spacing, !saving) {
                            onChange(draft.copy(spacing = spacing))
                        }
                    }
                }
                HorizontalDivider()
                Text("背景色调", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BriefingTone.entries.forEach { tone ->
                        DisplayChoice(tone.label, draft.background == tone.background, !saving, swatch = Color(tone.background)) {
                            hex = briefingColorText(tone.background)
                            onChange(draft.copy(background = tone.background))
                        }
                    }
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { value ->
                        hex = value
                        parseBriefingColor(value)?.let { onChange(draft.copy(background = it)) }
                    },
                    label = { Text("自定义背景色") },
                    placeholder = { Text("#CCCECA") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !saving,
                    isError = validColor == null,
                    supportingText = if (validColor == null) { { Text("请输入六位十六进制色值") } } else null,
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val defaults = BriefingAppearance()
                    hex = briefingColorText(defaults.background)
                    onChange(defaults)
                }, enabled = !saving) { Text("恢复默认") }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !saving && validColor != null, shape = controlShape) {
                Text(if (saving) "保存中…" else "保存")
            }
        },
    )
}

@Composable
private fun DisplaySlider(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    unit: String = " dp",
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(value.toString() + unit, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val number = range.first + (((it - range.first) / step).roundToInt() * step)
                onChange(number.coerceIn(range))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            enabled = enabled,
        )
    }
}

@Composable
private fun DisplayChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    swatch: Color? = null,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (swatch != null) Box(Modifier.size(14.dp).background(swatch))
            Text(label)
        }
    }
    if (selected) Button(onClick = onClick, enabled = enabled, shape = controlShape) { content() }
    else OutlinedButton(onClick = onClick, enabled = enabled, shape = controlShape) { content() }
}
