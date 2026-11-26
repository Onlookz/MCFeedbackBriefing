package com.lemon.mcdevmanagermp.briefing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lemon.mcdevmanagermp.ui.components.ImagePreviewOverlay
import com.lemon.mcdevmanagermp.ui.theme.AppColors
import com.lemon.mcdevmanagermp.ui.theme.LocalAppColors
import com.sun.jna.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

internal val controlShape = RoundedCornerShape(6.dp)
private const val SYNC_INTERVAL_MILLIS = 60 * 60 * 1000L
private const val BRIEFING_SOURCE_URL = "https://github.com/pangguanzhe0913/MCFeedbackBriefing"

@Composable
internal fun FeedbackBriefingApp(animateBackdrop: Boolean, onAppearance: () -> Unit) {
    val session = remember { BriefingAccount() }
    val store = remember { BriefingStore() }
    var restoring by remember { mutableStateOf(true) }
    var loginError by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf(BriefingAccountEntry()) }

    LaunchedEffect(Unit) {
        try {
            entry = withContext(Dispatchers.IO) { session.openOnStart() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loginError = "无法读取上次登录状态，请重新登录。"
        } finally {
            restoring = false
        }
    }

    val activeAccount = entry.active
    when {
        restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在读取登录状态…")
        }
        activeAccount == null -> Box(Modifier.fillMaxSize()) {
            BriefingAccountBackdrop(Modifier.matchParentSize(), moving = animateBackdrop)
            BriefingAccountPage(session, entry, loginError, onAppearance) {
                loginError = ""
                entry = BriefingAccountEntry(remembered = it, active = it)
            }
        }
        else -> BriefingScreen(
            accountId = activeAccount.id,
            store = store,
            onAppearance = onAppearance,
            onSignOut = {
                withContext(Dispatchers.IO) { session.signOut() }
                loginError = ""
                entry = BriefingAccountEntry(remembered = activeAccount)
            },
            onExpired = {
                session.clearSession()
                loginError = "网易会话已过期，请重新登录以继续同步。"
                entry = BriefingAccountEntry(remembered = activeAccount, needsLogin = true)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BriefingScreen(
    accountId: Long,
    store: BriefingStore,
    onAppearance: () -> Unit,
    onSignOut: suspend () -> Unit,
    onExpired: () -> Unit,
) {
    val sync = remember(accountId) { BriefingSync() }
    val replyService = remember(accountId) { BriefingReplies(ReplyStore(accountId)) }
    val replyScope = rememberCoroutineScope()
    var replyState by remember(accountId) { mutableStateOf<ReplyState?>(null) }
    var replyNotice by remember(accountId) { mutableStateOf("") }
    var showReplySettings by remember { mutableStateOf(false) }
    var replyTopic by remember(accountId) { mutableStateOf<BriefingTopic?>(null) }
    var manualSending by remember { mutableStateOf(false) }
    var autoSending by remember { mutableStateOf(false) }
    var snapshot by remember(accountId) { mutableStateOf(BriefingSnapshot()) }
    var projectDirectory by remember(accountId) { mutableStateOf(emptyMap<String, BriefingProject>()) }
    var projectDirectoryLoaded by remember(accountId) { mutableStateOf(false) }
    var projectNotice by remember(accountId) { mutableStateOf("") }
    val exportSettings = remember(accountId) { BriefingExportSettings(accountId) }
    var exportPlan by remember(accountId) { mutableStateOf<BriefingExportPlan?>(null) }
    var exportSnapshot by remember(accountId) { mutableStateOf<BriefingSnapshot?>(null) }
    var workbookPath by remember(accountId) { mutableStateOf<Path?>(null) }
    var workbookNotice by remember(accountId) { mutableStateOf("") }
    var openingWorkbook by remember(accountId) { mutableStateOf(false) }
    var exportingWorkbook by remember { mutableStateOf(false) }
    var connection by remember(store) { mutableStateOf(store.loadConnection()) }
    var dateFilter by remember(accountId, store) { mutableStateOf(store.loadDateFilter(accountId)) }
    var projectFilter by remember(accountId, store) { mutableStateOf(store.loadProjectFilter(accountId)) }
    var topicOrder by remember(accountId, store) { mutableStateOf(store.loadTopicOrder(accountId)) }
    var showDateRange by remember(accountId) { mutableStateOf(false) }
    var savingDateRange by remember(accountId) { mutableStateOf(false) }
    var dateRangeError by remember(accountId) { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var pictures by remember(accountId) { mutableStateOf(emptyList<String>()) }
    var originalTopic by remember(accountId) { mutableStateOf<Pair<String, BriefingTopic>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var signingOut by remember(accountId) { mutableStateOf(false) }
    var ready by remember(accountId) { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(accountId) {
        try {
            snapshot = withContext(Dispatchers.IO) { store.load(accountId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "上次简报无法读取，可以重新整理。"
        }
        try {
            replyState = replyService.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            replyNotice = "无法读取回复记录。请重开程序后再试。"
        } finally {
            ready = true
        }
    }

    LaunchedEffect(accountId, refresh) {
        if (refresh == 0) {
            try {
                projectDirectory = withContext(Dispatchers.IO) { store.loadProjects(accountId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                projectNotice = "上次的项目目录无法读取。"
            }
        }
        val projects = try {
            loadBriefingProjects()
        } catch (e: CancellationException) {
            throw e
        } catch (_: BriefingFailure) {
            projectNotice = "网易项目目录暂时无法更新。"
            return@LaunchedEffect
        }
        projectDirectory = projects
        projectDirectoryLoaded = true
        projectNotice = ""
        try {
            withContext(Dispatchers.IO) { store.saveProjects(accountId, projects) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            projectNotice = "本次项目目录已更新，缓存未能保存。"
        }
    }

    LaunchedEffect(accountId, connection, dateFilter, refresh, ready, paused) {
        if (!ready || connection.baseUrl.isBlank() || connection.model.isBlank()) return@LaunchedEffect
        if (paused) {
            delay(SYNC_INTERVAL_MILLIS)
            paused = false
            return@LaunchedEffect
        }
        while (true) {
            if (manualSending || originalTopic != null || showDateRange || savingDateRange || signingOut) {
                delay(1000L)
                continue
            }
            syncing = true
            error = ""
            try {
                val next = sync.update(connection, snapshot, dateFilter) { status = it }
                withContext(Dispatchers.IO) { store.save(accountId, next) }
                snapshot = next
                if (replyState != null) {
                    try {
                        val current = replyService.load()
                        replyState = current
                        val requests = next.autoReplies(current)
                        if (requests.isNotEmpty()) {
                            autoSending = true
                            val outcome = replyService.send(requests) { position, total ->
                                status = "正在自动回复 $position/$total…"
                            }
                            replyState = outcome.state
                            replyNotice = outcome.message(automatic = true)
                            if (outcome.sessionExpired) {
                                onExpired()
                                return@LaunchedEffect
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        replyNotice = "自动回复未能完成，请重开程序核对发送记录。"
                        replyState = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: BriefingSessionExpired) {
                onExpired()
                return@LaunchedEffect
            } catch (e: BriefingFailure) {
                error = e.message.orEmpty()
            } catch (_: Exception) {
                error = "本次简报未能保存，请重新整理。"
            } finally {
                autoSending = false
                syncing = false
            }
            delay(SYNC_INTERVAL_MILLIS)
        }
    }

    LaunchedEffect(accountId) {
        try {
            exportPlan = withContext(Dispatchers.IO) { exportSettings.load() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            workbookNotice = "无法读取上次导出设置。"
        }
    }

    LaunchedEffect(accountId, snapshot.updatedAt, exportPlan) {
        val plan = exportPlan?.takeIf { it.automatic } ?: return@LaunchedEffect
        if (snapshot.updatedAt <= 0L) return@LaunchedEffect
        val selected = snapshot.selectedBy(plan)
        if (selected.works.values.none { it.allTopics.isNotEmpty() }) return@LaunchedEffect

        exportingWorkbook = true
        workbookNotice = ""
        try {
            workbookPath = withContext(Dispatchers.IO) { BriefingWorkbook().export(accountId, selected, plan.format) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            workbookNotice = "自动保存 WPS 文件失败。"
        } finally {
            exportingWorkbook = false
        }
    }

    val busy = syncing || manualSending || signingOut
    fun applyDateFilter(next: BriefingDateFilter) {
        if (next == dateFilter) {
            showDateRange = false
            return
        }
        savingDateRange = true
        dateRangeError = ""
        replyScope.launch {
            try {
                withContext(Dispatchers.IO) { store.saveDateFilter(accountId, next) }
                paused = false
                dateFilter = next
                showDateRange = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                dateRangeError = "整理范围未能保存，请重试。"
            } finally {
                savingDateRange = false
            }
        }
    }
    val configured = connection.baseUrl.isNotBlank() && connection.model.isNotBlank()
    val visibleWorks = remember(projectDirectory, snapshot.works, projectFilter) {
        projectBriefings(projectDirectory, snapshot.works, projectFilter)
    }
    val emptyText = when {
        !configured -> "在 AI 连接中设置模型后开始整理。"
        syncing || !ready -> "正在准备简报…"
        snapshot.updatedAt == 0L && error.isNotBlank() -> "暂无可显示的简报。"
        snapshot.updatedAt == 0L -> "等待第一次整理完成。"
        snapshot.sourceWarnings.isNotEmpty() -> "已读取的来源暂无需要你处理的反馈。"
        else -> "这份简报中暂无需要处理的反馈。"
    }

    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        BriefingWorkspaceHeader(
            snapshot = snapshot,
            syncing = syncing,
            status = status,
            actions = {
                BriefingSettingsGroup(
                    onAppearance = onAppearance,
                    onReplies = { showReplySettings = true },
                    onConnection = { showSettings = true },
                    repliesEnabled = !busy && replyState != null,
                    connectionEnabled = !busy,
                )
                OutlinedButton(
                    enabled = !busy,
                    shape = controlShape,
                    onClick = {
                        signingOut = true
                        replyScope.launch {
                            try {
                                onSignOut()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                error = "退出账号失败，请重试。"
                            } finally {
                                signingOut = false
                            }
                        }
                    },
                ) { Text(if (signingOut) "退出中…" else "退出账号") }
                if (syncing) {
                    OutlinedButton(
                        onClick = {
                            paused = true
                            error = "已取消本次整理。"
                        },
                        enabled = !autoSending,
                        shape = controlShape,
                    ) { Text("取消整理") }
                } else {
                    Button(
                        onClick = {
                            paused = false
                            refresh++
                        },
                        enabled = ready && configured && !busy,
                        shape = controlShape,
                    ) { Text("立即整理") }
                }
            },
            filters = {
                BriefingDateMenu(
                    filter = dateFilter,
                    enabled = ready && !busy && !savingDateRange,
                    onLastDay = { applyDateFilter(BriefingDateFilter.LastDay) },
                    onCustom = {
                        dateRangeError = ""
                        showDateRange = true
                    },
                )
                ProjectFilterMenu(projectFilter) {
                    projectFilter = it
                    store.saveProjectFilter(accountId, it)
                }
                Text("每小时更新", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            },
            files = {
                if (snapshot.updatedAt > 0L) {
                    OutlinedButton(
                        onClick = { exportSnapshot = snapshot },
                        enabled = !exportingWorkbook,
                        shape = controlShape,
                    ) { Text(if (exportingWorkbook) "导出中…" else "导出 WPS") }
                }
                val file = workbookPath
                if (file != null) {
                    TextButton(
                        enabled = !openingWorkbook,
                        onClick = {
                            openingWorkbook = true
                            replyScope.launch {
                                try {
                                    val opened = withContext(Dispatchers.IO) {
                                        if (!Files.isRegularFile(file)) false
                                        else {
                                            Desktop.getDesktop().open(file.toFile())
                                            true
                                        }
                                    }
                                    if (!opened) {
                                        workbookPath = null
                                        workbookNotice = "该文件已移动，可以重新导出。"
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    workbookNotice = "无法打开 WPS 文件。已保存到 $file"
                                } finally {
                                    openingWorkbook = false
                                }
                            }
                        },
                    ) { Text(if (openingWorkbook) "打开中…" else "打开文件") }
                }
            },
            notices = {
                if (error.isNotBlank()) BriefingStatusNotice(error, error = !paused)
                if (dateRangeError.isNotBlank() && !showDateRange) BriefingStatusNotice(dateRangeError, error = true)
                if (workbookNotice.isNotBlank()) BriefingStatusNotice(workbookNotice)
                if (replyNotice.isNotBlank()) BriefingStatusNotice(replyNotice)
                if (projectNotice.isNotBlank()) {
                    Text(projectNotice, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (snapshot.sourceWarnings.isNotEmpty()) {
                    Text(
                        "上次整理 · " + snapshot.sourceWarnings.values.joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                snapshot.coverageWarning?.let { warning ->
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            },
        )
        HorizontalDivider(color = colors.outlineVariant)
        if (visibleWorks.isEmpty()) {
            val directoryEmptyText = when {
                projectDirectoryLoaded && projectFilter == ProjectFilter.ALL -> "暂无项目。"
                projectDirectoryLoaded -> "当前没有已上架的项目。"
                projectNotice.isNotBlank() -> "暂时无法读取项目目录。"
                else -> "正在读取项目目录…"
            }
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Surface(
                    shape = controlShape,
                    color = colors.surfaceContainer,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    Text(
                        directoryEmptyText,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        } else {
            ProjectBriefings(
                works = visibleWorks,
                filter = projectFilter,
                emptyText = emptyText,
                order = topicOrder,
                onOrderChange = {
                    topicOrder = it
                    store.saveTopicOrder(accountId, it)
                },
                replyState = replyState,
                repliesEnabled = !busy,
                onReply = { replyTopic = it },
                onPictures = { pictures = it },
                onOriginals = { workKey, topic -> originalTopic = workKey to topic },
            )
        }
    }

    if (showDateRange) {
        BriefingDateRangeDialog(
            current = dateFilter,
            saving = savingDateRange,
            error = dateRangeError,
            onDismiss = {
                showDateRange = false
                dateRangeError = ""
            },
            onSave = ::applyDateFilter,
        )
    }

    val reportForExport = exportSnapshot
    if (reportForExport != null) {
        BriefingExportDialog(reportForExport, exportPlan, onDismiss = { exportSnapshot = null }) { plan, now ->
            withContext(Dispatchers.IO) { exportSettings.save(plan) }
            exportPlan = plan
            if (now) {
                val selected = reportForExport.selectedBy(plan)
                workbookPath = withContext(Dispatchers.IO) { BriefingWorkbook().export(accountId, selected, plan.format) }
                workbookNotice = "已保存 " + workbookPath?.fileName
            } else {
                workbookNotice = ""
            }
            exportSnapshot = null
        }
    }

    val currentReplyState = replyState
    if (showReplySettings && currentReplyState != null) {
        ReplySettingsDialog(currentReplyState.settings, onDismiss = { showReplySettings = false }) { next ->
            replyState = replyService.saveSettings(next)
            replyNotice = "回复设置已保存。"
            showReplySettings = false
        }
    }
    val topicForReply = replyTopic
    if (topicForReply != null && currentReplyState != null) {
        QuickReplyDialog(
            topic = topicForReply,
            targets = topicForReply.replyTargets.filter { it.key !in currentReplyState.attempts },
            settings = currentReplyState.settings,
            busy = busy,
            onDismiss = { replyTopic = null },
            onSend = { requests ->
                manualSending = true
                replyNotice = ""
                replyScope.launch {
                    try {
                        val outcome = replyService.send(requests)
                        replyState = outcome.state
                        replyNotice = outcome.message(automatic = false)
                        replyTopic = null
                        if (outcome.sessionExpired) onExpired()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        replyNotice = "本次回复未能完成，请重开程序核对发送记录。"
                        replyTopic = null
                        replyState = null
                    } finally {
                        manualSending = false
                    }
                }
            },
        )
    }
    if (pictures.isNotEmpty()) {
        FeedbackPicturesDialog(pictures, onDismiss = { pictures = emptyList() })
    }
    originalTopic?.let { (workKey, topic) ->
        FeedbackOriginalsDialog(
            title = topic.title,
            canConfirm = !busy,
            onDismiss = { originalTopic = null },
            onExpired = onExpired,
            load = { sync.loadOriginals(workKey, topic) },
        )
    }
    if (showSettings) {
        AiConnectionDialog(connection, onDismiss = { showSettings = false }) { next ->
            withContext(Dispatchers.IO) { store.saveConnection(next) }
            paused = false
            connection = next
            showSettings = false
        }
    }
}

@Composable
private fun ProjectFilterMenu(filter: ProjectFilter, onChange: (ProjectFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = controlShape) {
            Text("显示项目 · ${filter.label}  ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ProjectFilter.entries.forEach { option ->
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

@Composable
private fun FeedbackPicturesDialog(pictures: List<String>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var page by remember(pictures) { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalDensity provides density) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.88f),
                shape = RoundedCornerShape(8.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "反馈截图 · " + (page + 1) + "/" + pictures.size,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (pictures.size > 1) {
                            OutlinedButton(onClick = { page-- }, enabled = page > 0, shape = controlShape) {
                                Text("上一张")
                            }
                            OutlinedButton(
                                onClick = { page++ },
                                enabled = page < pictures.lastIndex,
                                shape = controlShape,
                            ) { Text("下一张") }
                        }
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    Box(Modifier.weight(1f)) {
                        val appColors = AppColors(colors, LocalAppColors.current.extended)
                        CompositionLocalProvider(LocalAppColors provides appColors) {
                            key(page) {
                                ImagePreviewOverlay(imageUrl = pictures[page], onDismiss = onDismiss)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiConnectionDialog(
    connection: AiConnection,
    onDismiss: () -> Unit,
    onSave: suspend (AiConnection) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var baseUrl by remember { mutableStateOf(connection.baseUrl) }
    var model by remember { mutableStateOf(connection.model) }
    var key by remember { mutableStateOf(connection.apiKey) }
    var rememberKey by remember { mutableStateOf(connection.rememberApiKey) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(connection.restoreWarning.orEmpty()) }

    BriefingDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("AI 连接") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("选用支持 JSON 输出的 OpenAI 兼容模型。经脱敏的反馈正文会发送到这个地址。")
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API 基础地址") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名称") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key（按服务要求填写）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (Platform.isWindows()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = rememberKey,
                            enabled = !saving,
                            role = Role.Checkbox,
                            onValueChange = { rememberKey = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = rememberKey, onCheckedChange = null, enabled = !saving)
                        Text("记住 API Key")
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                Text(
                    "基于 MCDevManager v1.2.11 修改 · 老曾 · 2026-11-26 至 2026-11-27\nBNCL-1.0 源码可见许可证",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { uriHandler.openUri(BRIEFING_SOURCE_URL) }, shape = controlShape) {
                    Text(BRIEFING_SOURCE_URL, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    saving = true
                    error = ""
                    scope.launch {
                        try {
                            val next = AiConnection(
                                baseUrl.trim(),
                                model.trim(),
                                key.trim(),
                                rememberApiKey = rememberKey && key.isNotBlank() && Platform.isWindows(),
                            )
                            next.endpoint()
                            onSave(next)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: BriefingFailure) {
                            error = e.message.orEmpty()
                        } catch (_: Exception) {
                            error = "AI 连接设置无法保存。"
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                shape = controlShape,
            ) { Text("保存并开始整理") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}
