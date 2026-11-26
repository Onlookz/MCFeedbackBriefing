package com.lemon.mcdevmanagermp.briefing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter

internal val briefingPanelShape = RoundedCornerShape(9.dp)
internal val updatedAtFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(briefingZone)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BriefingWorkspaceHeader(
    snapshot: BriefingSnapshot,
    syncing: Boolean,
    status: String,
    actions: @Composable () -> Unit,
    filters: @Composable () -> Unit,
    files: @Composable () -> Unit,
    notices: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = LocalBriefingAppearance.current.spacing
    val width = LocalBriefingViewport.current.width
    val fontScale = LocalDensity.current.fontScale
    val heading: @Composable () -> Unit = {
        Text("玩家反馈简报", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
    Surface(color = colors.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = (spacing.pagePadding + 6).dp, vertical = spacing.pagePadding.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.gap.dp),
        ) {
            if (width >= (640 * fontScale).dp) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { heading() }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) { actions() }
                }
            } else {
                heading()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) { actions() }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = briefingPanelShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                if (width >= (760 * fontScale).dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlowRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) { filters() }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { files() }
                    }
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        filters()
                        files()
                    }
                }
            }

            if (snapshot.updatedAt > 0L) {
                val updated = updatedAtFormat.format(Instant.ofEpochMilli(snapshot.updatedAt))
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(15.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "上次整理 $updated · ${snapshot.windowLabel}",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SourceSummary(Source.COMMENT, snapshot.commentCount, Source.COMMENT in snapshot.sourceWarnings)
                    SourceSummary(Source.FEEDBACK, snapshot.feedbackCount, Source.FEEDBACK in snapshot.sourceWarnings)
                }
            }
            if (syncing) {
                Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(status.ifBlank { "正在准备整理…" }, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = colors.primary,
                        trackColor = colors.surfaceContainerHighest,
                    )
                }
            }
            notices()
        }
    }
}

@Composable
internal fun BriefingSettingsGroup(
    onAppearance: () -> Unit,
    onReplies: () -> Unit,
    onConnection: () -> Unit,
    repliesEnabled: Boolean,
    connectionEnabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = controlShape,
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAppearance, shape = controlShape) { Text("显示设置") }
            VerticalDivider(Modifier.height(18.dp), color = colors.outlineVariant)
            TextButton(onClick = onReplies, enabled = repliesEnabled, shape = controlShape) { Text("回复设置") }
            VerticalDivider(Modifier.height(18.dp), color = colors.outlineVariant)
            TextButton(onClick = onConnection, enabled = connectionEnabled, shape = controlShape) { Text("AI 连接") }
        }
    }
}

@Composable
private fun SourceSummary(source: Source, count: Int, failed: Boolean) {
    val colors = MaterialTheme.colorScheme
    Text(
        if (failed) "${source.label}未更新" else "${source.label} $count 条",
        color = if (failed) colors.error else colors.onSurface,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
internal fun BriefingStatusNotice(message: String, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val accent = if (error) colors.error else colors.primary
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = controlShape,
        color = if (error) colors.errorContainer else colors.surfaceContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 3.dp, height = 14.dp).clip(controlShape).background(accent))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun BriefingInteractiveSurface(
    onClick: () -> Unit,
    label: String,
    background: Color,
    selectedItem: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tint = when {
        pressed -> 0.20f
        selectedItem == true -> if (hovered) 0.17f else 0.13f
        hovered || focused -> 0.07f
        else -> 0f
    }
    val fill by animateColorAsState(lerp(background, colors.primary, tint), tween(120), label = "控件底色")
    Surface(
        modifier = Modifier.fillMaxWidth().clip(controlShape).pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { if (selectedItem != null) selected = selectedItem },
        shape = controlShape,
        color = fill,
        border = when {
            selectedItem == true -> BorderStroke(1.dp, colors.primary.copy(alpha = 0.70f))
            hovered || focused -> BorderStroke(1.dp, colors.outline)
            else -> BorderStroke(1.dp, colors.outlineVariant)
        },
        content = content,
    )
}
