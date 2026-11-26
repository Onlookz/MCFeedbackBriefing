package com.lemon.mcdevmanagermp.briefing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant

@Composable
internal fun ProjectBriefings(
    works: List<ProjectBriefingRow>,
    filter: ProjectFilter,
    emptyText: String,
    order: BriefingTopicOrder,
    onOrderChange: (BriefingTopicOrder) -> Unit,
    replyState: ReplyState?,
    repliesEnabled: Boolean,
    onReply: (BriefingTopic) -> Unit,
    onPictures: (List<String>) -> Unit,
    onOriginals: (String, BriefingTopic) -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var openMenu by remember { mutableStateOf(false) }
    val selected = works.firstOrNull { it.key == selectedId }
    val appearance = LocalBriefingAppearance.current
    val spacing = appearance.spacing
    val contents: @Composable () -> Unit = {
        ProjectContents(selected, emptyText, order, onOrderChange, replyState, repliesEnabled, onReply, onPictures, onOriginals)
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(spacing.pagePadding.dp)) {
        if (appearance.placement != ProjectPlacement.TOP && maxWidth >= 690.dp) {
            val sidebarWidth = minOf(appearance.sidebarWidth.dp, maxWidth - 410.dp - spacing.gap.dp)
            val select: (String) -> Unit = { id -> selectedId = id.takeUnless { it == selectedId } }
            val sidebar: @Composable () -> Unit = {
                ProjectSidebar(works, filter, selected?.key, sidebarWidth, select)
            }
            val placeRight = appearance.placement == ProjectPlacement.RIGHT
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacing.gap.dp)) {
                if (!placeRight) sidebar()
                Box(Modifier.weight(1f).fillMaxHeight()) { contents() }
                if (placeRight) sidebar()
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing.gap.dp)) {
                Box {
                    OutlinedButton(onClick = { openMenu = true }, shape = controlShape) {
                        Text(
                            (selected?.name ?: "选择项目") + "  ▾",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = openMenu,
                        onDismissRequest = { openMenu = false },
                        modifier = Modifier.heightIn(max = minOf(340.dp, LocalBriefingViewport.current.height * 0.55f)),
                    ) {
                        if (selected != null) {
                            DropdownMenuItem(text = { Text("收起当前项目") }, onClick = {
                                selectedId = null
                                openMenu = false
                            })
                        }
                        works.forEach { entry ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(entry.name)
                                        if (filter == ProjectFilter.ALL) ProjectStatusText(entry.project)
                                    }
                                },
                                trailingIcon = { ProjectTopicCount(entry.allTopics.size, selected?.key == entry.key) },
                                onClick = {
                                    selectedId = entry.key
                                    openMenu = false
                                },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { contents() }
            }
        }
    }
}

@Composable
private fun ProjectSidebar(
    works: List<ProjectBriefingRow>,
    filter: ProjectFilter,
    selectedId: String?,
    width: Dp,
    onSelect: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = LocalBriefingAppearance.current.spacing
    val listState = rememberLazyListState()
    Surface(
        modifier = Modifier.width(width).fillMaxHeight(),
        shape = briefingPanelShape,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column {
            Surface(color = colors.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = (spacing.rowPadding / 2 + 3).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (filter == ProjectFilter.PUBLISHED) "已上架项目" else "全部项目",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("${works.size} 个", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 7.dp, end = 11.dp, top = 7.dp, bottom = 7.dp),
                    verticalArrangement = Arrangement.spacedBy((spacing.gap / 2).coerceAtLeast(4).dp),
                ) {
                    items(works, key = { it.key }) { entry ->
                        val active = selectedId == entry.key
                        BriefingInteractiveSurface(
                            onClick = { onSelect(entry.key) },
                            label = if (active) "收起项目" else "查看项目反馈",
                            background = colors.surfaceContainer,
                            selectedItem = active,
                        ) {
                            Box {
                                if (active) {
                                    Box(
                                        Modifier.align(Alignment.CenterStart).width(3.dp).height(26.dp)
                                            .clip(controlShape).background(colors.primary),
                                    )
                                }
                                Row(
                                    Modifier.defaultMinSize(minHeight = 42.dp).padding(
                                        horizontal = 9.dp,
                                        vertical = (spacing.rowPadding / 2 + 1).dp,
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            entry.name,
                                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                        if (filter == ProjectFilter.ALL) ProjectStatusText(entry.project)
                                    }
                                    ProjectTopicCount(entry.allTopics.size, active)
                                    Text(if (active) "▾" else "▸", color = if (active) colors.primary else colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                ProjectScrollbar(listState)
            }
        }
    }
}

@Composable
private fun ProjectTopicCount(count: Int, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    val color = when {
        active -> colors.primary
        count > 0 -> colors.primaryContainer
        else -> Color.Transparent
    }
    val ink = when {
        active -> colors.onPrimary
        count > 0 -> colors.onPrimaryContainer
        else -> colors.onSurfaceVariant
    }
    Surface(
        shape = controlShape,
        color = color,
        contentColor = ink,
        border = if (count == 0 && !active) BorderStroke(1.dp, colors.outlineVariant) else null,
    ) {
        Text(
            count.toString(),
            modifier = Modifier.defaultMinSize(minWidth = 30.dp).padding(horizontal = 5.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (count > 0) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProjectStatusText(project: BriefingProject) {
    val colors = MaterialTheme.colorScheme
    Text(
        project.statusLabel,
        style = MaterialTheme.typography.bodySmall,
        color = if (project.isPublished) colors.primary else colors.onSurfaceVariant,
    )
}

@Composable
private fun ProjectContents(
    selected: ProjectBriefingRow?,
    emptyText: String,
    order: BriefingTopicOrder,
    onOrderChange: (BriefingTopicOrder) -> Unit,
    replyState: ReplyState?,
    repliesEnabled: Boolean,
    onReply: (BriefingTopic) -> Unit,
    onPictures: (List<String>) -> Unit,
    onOriginals: (String, BriefingTopic) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = LocalBriefingAppearance.current.spacing
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = briefingPanelShape,
        color = colors.surfaceContainerLowest,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        if (selected == null) {
            ProjectEmpty("选择一个项目查看反馈。", Modifier.fillMaxSize())
        } else {
            val topics = remember(selected.briefing, order) { order.apply(selected.allTopics) }
            Column {
                Surface(color = colors.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selected.name,
                            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (topics.isNotEmpty()) TopicOrderMenu(order, onOrderChange)
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                if (topics.isEmpty()) {
                    ProjectEmpty(emptyText, Modifier.weight(1f).fillMaxWidth())
                } else {
                    key(selected.key, order) {
                        val listState = rememberLazyListState()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = listState,
                                contentPadding = PaddingValues(start = 9.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(spacing.gap.dp),
                            ) {
                                items(topics) { topic ->
                                    TopicSummary(topic, replyState, repliesEnabled, onReply, onPictures) {
                                        onOriginals(selected.key, topic)
                                    }
                                }
                            }
                            ProjectScrollbar(listState)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectEmpty(message: String, modifier: Modifier) {
    Box(modifier.padding(20.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BoxScope.ProjectScrollbar(state: LazyListState) {
    if (!state.canScrollBackward && !state.canScrollForward) return
    val colors = MaterialTheme.colorScheme
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 3.dp, top = 4.dp, bottom = 4.dp),
        style = ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = 4.dp,
            shape = controlShape,
            hoverDurationMillis = 120,
            unhoverColor = colors.outline.copy(alpha = 0.55f),
            hoverColor = colors.primary,
        ),
    )
}

@Composable
private fun TopicOrderMenu(order: BriefingTopicOrder, onChange: (BriefingTopicOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = controlShape) {
            Text("排序 · ${order.label}  ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BriefingTopicOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicSummary(
    topic: BriefingTopic,
    replyState: ReplyState?,
    repliesEnabled: Boolean,
    onReply: (BriefingTopic) -> Unit,
    onPictures: (List<String>) -> Unit,
    onOriginals: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = LocalBriefingAppearance.current.spacing
    val accent = when (topic.kind) {
        TopicKind.ISSUE -> colors.error
        TopicKind.SUGGESTION -> colors.primary
        TopicKind.OBSERVE -> colors.secondary
    }
    val first = topic.firstFeedbackAt
    val last = topic.latestFeedbackAt
    val timeText = when {
        first == null || last == null -> "反馈时间待同步"
        first == last -> "反馈时间 " + updatedAtFormat.format(Instant.ofEpochMilli(first))
        else -> "反馈时间 " + updatedAtFormat.format(Instant.ofEpochMilli(first)) +
            " 至 " + updatedAtFormat.format(Instant.ofEpochMilli(last))
    }
    val participants = when {
        topic.players == 0 -> ""
        topic.unknownPlayers -> " · 至少 ${topic.players} 位玩家"
        else -> " · ${topic.players} 位玩家"
    }
    BriefingInteractiveSurface(onClick = onOriginals, label = "查看原文", background = colors.surface) {
        Column(
            Modifier.padding(horizontal = 13.dp, vertical = spacing.rowPadding.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(shape = controlShape, color = lerp(colors.surfaceContainer, accent, 0.13f)) {
                    Text(
                        topic.kind.label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurface,
                    )
                }
                Text(
                    topic.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (topic.trigger.isNotBlank()) {
                Text(topic.trigger, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(timeText, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text("${topic.records} 条$participants", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text("查看原文 ›", color = colors.primary, style = MaterialTheme.typography.labelMedium)
            }
            val canReply = replyState != null && topic.replyTargets.any { it.key !in replyState.attempts }
            if (canReply || topic.imageUrls.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (canReply) {
                        TextButton(
                            onClick = { onReply(topic) },
                            enabled = repliesEnabled,
                            shape = controlShape,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("一键回复") }
                    }
                    if (topic.imageUrls.isNotEmpty()) {
                        TextButton(
                            onClick = { onPictures(topic.imageUrls) },
                            shape = controlShape,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("查看截图（${topic.imageUrls.size} 张）") }
                    }
                }
            }
        }
    }
}
