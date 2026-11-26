package com.lemon.mcdevmanagermp.briefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
internal fun BriefingDateMenu(
    filter: BriefingDateFilter,
    enabled: Boolean,
    onLastDay: () -> Unit,
    onCustom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, shape = controlShape) {
            Text("整理范围 · ${filter.label}  ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("最近 1 天（滚动 24 小时）") },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onLastDay()
                },
            )
            DropdownMenuItem(
                text = { Text("自定义日期…") },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onCustom()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BriefingDateRangeDialog(
    current: BriefingDateFilter,
    saving: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onSave: (BriefingDateFilter.Custom) -> Unit,
) {
    val today = remember { LocalDate.now(briefingZone) }
    val custom = current as? BriefingDateFilter.Custom
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (custom?.start ?: today).toPickerMillis(),
        initialSelectedEndDateMillis = (custom?.end ?: today).toPickerMillis(),
        initialDisplayedMonthMillis = (custom?.end ?: today).toPickerMillis(),
        selectableDates = remember(today) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= today.toPickerMillis()

                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            }
        },
    )
    val start = state.selectedStartDateMillis?.let(::fromPickerMillis)
    val end = state.selectedEndDateMillis?.let(::fromPickerMillis)
    BriefingDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("自定义整理日期") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("按北京时间，包含起止日期当天。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                DateRangePicker(
                    state,
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    title = null,
                    headline = {
                        Text(
                            "${start ?: "开始日期"} 至 ${end ?: "结束日期"}",
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (start != null && end != null) onSave(BriefingDateFilter.Custom(start, end))
                },
                enabled = !saving && start != null && end != null && start <= end,
                shape = controlShape,
            ) { Text(if (saving) "保存中…" else "应用日期") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !saving, shape = controlShape) { Text("取消") }
        },
    )
}

private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun fromPickerMillis(value: Long): LocalDate =
    Instant.ofEpochMilli(value).atOffset(ZoneOffset.UTC).toLocalDate()
