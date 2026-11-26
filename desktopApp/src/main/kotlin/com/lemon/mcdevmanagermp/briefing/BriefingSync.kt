package com.lemon.mcdevmanagermp.briefing

import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.consts.CookiesExpiredException
import com.lemon.mcdevmanagermp.data.consts.LoginException
import com.lemon.mcdevmanagermp.data.repository.CommentRepositoryImpl
import com.lemon.mcdevmanagermp.data.repository.FeedbackRepositoryImpl
import com.lemon.mcdevmanagermp.domain.comment.CommentRepository
import com.lemon.mcdevmanagermp.domain.feedback.FeedbackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.IOException
import java.time.Instant

private const val PAGE_SIZE = 20
private const val SYNC_PAGE_SIZE = 100
private const val OLDER_ROWS_TO_CONFIRM = 20
private const val PAGES_IN_FLIGHT = 4

internal class BriefingSync(
    private val model: AiBriefing = AiBriefing(),
    private val commentRepository: CommentRepository = CommentRepositoryImpl.INSTANCE,
    private val feedbackRepository: FeedbackRepository = FeedbackRepositoryImpl.INSTANCE,
) {
    private var recentEntries = emptyMap<String, PlayerEntry>()

    suspend fun update(
        connection: AiConnection,
        previous: BriefingSnapshot,
        filter: BriefingDateFilter,
        status: (String) -> Unit,
    ): BriefingSnapshot {
        connection.endpoint()
        status("正在读取评论和玩家反馈…")
        val window = filter.windowAt(Instant.now())
        val comments = readWindow(Source.COMMENT, window, status)
        val feedback = readWindow(Source.FEEDBACK, window, status)
        val warnings = listOf(Source.COMMENT to comments, Source.FEEDBACK to feedback)
            .mapNotNull { (source, result) -> result.exceptionOrNull()?.message?.let { source to it } }
            .toMap()
        if (comments.isFailure && feedback.isFailure) {
            throw BriefingFailure(warnings.values.joinToString(" "))
        }

        val commentEntries = comments.getOrNull().orEmpty()
        val feedbackEntries = feedback.getOrNull().orEmpty()
        val entries = (commentEntries + feedbackEntries)
            .filter { it.text.isNotBlank() || it.imageUrls.isNotEmpty() }
        if (warnings.isNotEmpty() && entries.isEmpty() && previous.works.values.any { it.topics.isNotEmpty() || it.imageOnlyFeedback != null }) {
            throw BriefingFailure(warnings.values.joinToString(" ") + "已读取的来源暂无可整理内容，暂时显示上次简报。")
        }

        status("正在生成简报…")
        val works = model.review(connection, entries, previous.works, status)
        recentEntries = entries.associateBy(PlayerEntry::key)
        return BriefingSnapshot(
            updatedAt = Instant.now().toEpochMilli(),
            works = works,
            commentCount = commentEntries.size,
            feedbackCount = feedbackEntries.size,
            sourceWarnings = warnings,
            window = window,
        )
    }

    private suspend fun readWindow(
        source: Source,
        window: BriefingWindow,
        status: (String) -> Unit,
    ): Result<List<PlayerEntry>> {
        status("正在按时间读取${source.label}…")
        return readSource(
            source,
            stopBefore = window.startAt,
            onPage = { checked, matched ->
                status("正在筛选${source.label} · 已检查 $checked 条 · 日期内 $matched 条…")
            },
        ) { it.publishedAt in window }
    }

    suspend fun loadOriginals(workKey: String, topic: BriefingTopic): BriefingOriginals {
        val references = topic.originalReferencesOrLegacy()
        if (references.isEmpty()) {
            throw BriefingFailure("这张旧卡片缺少原文索引。请点「立即整理」更新简报。")
        }
        val found = references.mapNotNull { reference ->
            recentEntries[reference.key]?.takeIf { it.workKey == workKey && it.source == reference.source }
        }.associateBy(PlayerEntry::key).toMutableMap()
        val warnings = mutableListOf<String>()
        for ((source, missing) in references.filter { it.key !in found }.groupBy(OriginalReference::source)) {
            val keys = missing.map(OriginalReference::key).toSet()
            val result = readSource(source, stopAtMatches = keys.size) { entry ->
                entry.workKey == workKey && entry.key in keys
            }
            result.fold(
                onSuccess = { entries -> entries.forEach { found[it.key] = it } },
                onFailure = { error -> warnings += error.message.orEmpty() },
            )
        }
        val entries = references.mapNotNull { found[it.key] }.sortedByDescending(PlayerEntry::publishedAt)
        return BriefingOriginals(entries, references.size - entries.size, warnings)
    }

    private suspend fun loadPage(source: Source, offset: Int, pageSize: Int): Page = when (source) {
        Source.COMMENT -> {
            val page = readPageWithRetry(source) {
                commentRepository.getCommentList(start = offset, span = pageSize)
            }
            Page(page.count, page.data.map {
                PlayerEntry(source, it.id, it.iid, it.resName, it.uid, it.publishTime, it.userComment)
            })
        }
        Source.FEEDBACK -> {
            val page = readPageWithRetry(source) {
                feedbackRepository.loadFeedback(from = offset, size = pageSize)
            }
            Page(page.count, page.data.map {
                PlayerEntry(
                    source, it.id, it.iid, it.resName, it.commitUid, it.createTime, it.content,
                    imageUrls = it.picList.mapNotNull(::feedbackImageUrl).distinct(),
                    replyAllowed = !it.forbidReply && it.reply.isNullOrBlank(),
                )
            })
        }
    }

    private suspend fun <T : Any> readPageWithRetry(
        source: Source,
        load: suspend () -> NetworkState<T>,
    ): T {
        val result = load()
        if (result is NetworkState.Error && result.e is IOException) {
            delay(350)
            return load().requireData(source)
        }
        return result.requireData(source)
    }

    private suspend fun readSource(
        source: Source,
        stopBefore: Long? = null,
        stopAtMatches: Int? = null,
        onPage: (Int, Int) -> Unit = { _, _ -> },
        matches: (PlayerEntry) -> Boolean,
    ): Result<List<PlayerEntry>> {
        return try {
            Result.success(readPages(source, stopBefore, stopAtMatches, onPage, matches))
        } catch (e: BriefingFailure) {
            Result.failure(e)
        }
    }

    private suspend fun readPages(
        source: Source,
        stopBefore: Long?,
        stopAtMatches: Int?,
        onPage: (Int, Int) -> Unit,
        matches: (PlayerEntry) -> Boolean,
    ): List<PlayerEntry> {
        val entries = linkedMapOf<String, PlayerEntry>()
        val boundary = stopBefore?.let(::DateBoundary)
        var offset = 0
        var total = 0

        fun accept(page: Page): Boolean {
            total = page.total
            for (entry in page.entries) {
                if (!matches(entry)) continue
                entries.putIfAbsent(entry.key, entry)
                if (entries.size == stopAtMatches) return true
            }
            offset += page.entries.size
            if (page.entries.isEmpty() && offset < total) {
                throw BriefingFailure("网易" + source.label + "列表返回不完整，请稍后重试。")
            }
            if (offset > 0) onPage(offset, entries.size)
            return offset >= total || boundary?.isPast(page.entries) == true
        }

        var pageSize = if (boundary != null) SYNC_PAGE_SIZE else PAGE_SIZE
        val first = try {
            loadPage(source, 0, pageSize)
        } catch (e: BriefingFailure) {
            if (pageSize == PAGE_SIZE) throw e
            pageSize = PAGE_SIZE
            loadPage(source, 0, pageSize)
        }
        if (accept(first)) return entries.values.toList()
        var canPrefetch = first.entries.size == pageSize
        while (offset < total) {
            if (!canPrefetch || total - offset <= pageSize) {
                if (accept(loadPage(source, offset, pageSize))) break
                continue
            }
            val offsets = (offset until total step pageSize).take(PAGES_IN_FLIGHT)
            val complete = coroutineScope {
                val pages = offsets.map { start ->
                    async {
                        try {
                            Result.success(loadPage(source, start, pageSize))
                        } catch (e: BriefingFailure) {
                            Result.failure(e)
                        }
                    }
                }
                try {
                    for (page in pages) {
                        val result = page.await()
                        if (result.isFailure) {
                            // 服务器限制并发或单页出错时，从失败的位置改为逐页读取。
                            canPrefetch = false
                            break
                        }
                        val loaded = result.getOrThrow()
                        val knownTotal = total
                        if (accept(loaded)) return@coroutineScope true
                        if (loaded.entries.size != pageSize || loaded.total != knownTotal) {
                            // 后续预读的偏移按完整页计算，从实际读到的位置续读。
                            canPrefetch = false
                            break
                        }
                    }
                    false
                } finally {
                    pages.forEach { it.cancel() }
                }
            }
            if (complete) break
        }
        return entries.values.toList()
    }

    private fun <T : Any> NetworkState<T>.requireData(source: Source): T = when (this) {
        is NetworkState.Success -> data ?: throw BriefingFailure("网易" + source.label + "列表返回为空，请稍后重试。")
        is NetworkState.Error -> {
            if (e is LoginException || e is CookiesExpiredException) throw BriefingSessionExpired()
            val reason = when {
                msg == "服务器异常" -> "网易接口返回服务器异常"
                e is IOException -> "网络连接异常"
                else -> "网易接口返回错误"
            }
            throw BriefingFailure(source.label + "读取失败（" + reason + "）。")
        }
    }

    private data class Page(val total: Int, val entries: List<PlayerEntry>)

    private class DateBoundary(private val startAt: Long) {
        private var previous = Long.MAX_VALUE
        private var olderRows = 0
        private var newestFirst = true

        fun isPast(entries: List<PlayerEntry>): Boolean {
            if (!newestFirst) return false
            for (entry in entries) {
                val time = entry.publishedAt
                if (time <= 0 || time > previous) {
                    // 如果接口返回顺序变化，继续全量分页，避免漏掉范围内的数据。
                    newestFirst = false
                    return false
                }
                previous = time
                if (time < startAt) olderRows++
            }
            return olderRows >= OLDER_ROWS_TO_CONFIRM
        }
    }
}

internal data class BriefingOriginals(
    val entries: List<PlayerEntry>,
    val missing: Int,
    val warnings: List<String>,
)
