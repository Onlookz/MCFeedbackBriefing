package com.lemon.mcdevmanagermp.briefing

import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.common.NoNeedData
import com.lemon.mcdevmanagermp.data.consts.CookiesExpiredException
import com.lemon.mcdevmanagermp.data.dto.netease.feedback.DeveloperFeedbackDTO
import com.lemon.mcdevmanagermp.data.dto.netease.feedback.ReplyDTO
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentListVO
import com.lemon.mcdevmanagermp.data.vo.netease.feedback.DeveloperFeedbackVO
import com.lemon.mcdevmanagermp.data.vo.netease.feedback.FeedbackData
import com.lemon.mcdevmanagermp.data.vo.netease.feedback.FeedbackVO
import com.lemon.mcdevmanagermp.domain.comment.CommentRepository
import com.lemon.mcdevmanagermp.domain.feedback.FeedbackRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BriefingSyncTest {
    @Test
    fun filtersBothEndsOfTheRollingDayUsingTheOriginalQuery() = runBlocking {
        val now = Instant.now()
        val recent = comment("new", now.minus(1, ChronoUnit.HOURS).epochSecond, "联机掉线")
        val older = comment("old", now.minus(25, ChronoUnit.HOURS).epochSecond, "超出日期范围的旧评论")
        val future = comment("future", now.plus(1, ChronoUnit.HOURS).epochSecond, "尚未提交的评论")
        val comments = Comments(listOf(older, recent, future))

        withMockAi(listOf(oneIssue)) { connection, _, payload ->
            val result = BriefingSync(commentRepository = comments, feedbackRepository = Feedback())
                .update(connection, BriefingSnapshot(), BriefingDateFilter.LastDay) {}

            assertEquals(listOf(0 to 100), comments.requests)
            assertEquals(1, result.commentCount)
            assertEquals(0, result.feedbackCount)
            assertTrue(result.sourceWarnings.isEmpty())
            assertEquals(1, result.works.getValue("id:42").topics.single().records)
            val window = checkNotNull(result.window)
            assertEquals(86_400_000L, window.endBefore - window.startAt)
            assertTrue(payload.get().contains("联机掉线"))
            assertFalse(payload.get().contains("超出日期范围的旧评论"))
        }
    }

    @Test
    fun stopsAtTheDateBoundaryEvenWhenTheArchiveReports9999() = runBlocking {
        val day = LocalDate.of(2026, 11, 2)
        val exactStart = Instant.parse("2026-11-01T16:00:00Z").epochSecond
        val dateRows = List(19) { comment("current-$it", exactStart + 18 - it, "当天零点分界后的评论") } +
            List(81) { comment("old-$it", exactStart - 1 - it, "日期外的历史评论") }
        val comments = Comments { offset, span ->
            assertEquals(0, offset)
            assertEquals(100, span)
            NetworkState.Success(CommentListVO(9999, dateRows))
        }
        val feedback = Feedback(NetworkState.Success(FeedbackVO(
            List(100) { FeedbackData(id = "f-$it", createTime = exactStart - 1 - it) },
            232,
        )))
        val progress = mutableListOf<String>()
        withMockAi(listOf(oneIssue)) { connection, _, payload ->
            val snapshot = BriefingSync(commentRepository = comments, feedbackRepository = feedback)
                .update(connection, BriefingSnapshot(), BriefingDateFilter.Custom(day, day), progress::add)

            assertEquals(listOf(0 to 100), comments.requests)
            assertEquals(19, snapshot.commentCount)
            assertEquals(0, snapshot.feedbackCount)
            assertTrue(snapshot.sourceWarnings.isEmpty())
            assertTrue(payload.get().contains("当天零点分界后的评论"))
            assertFalse(payload.get().contains("日期外的历史评论"))
            assertTrue(progress.contains("正在筛选评论 · 已检查 100 条 · 日期内 19 条…"))
            assertTrue(progress.none { it.contains("9999") })
        }
    }

    @Test
    fun keepsPagingWhenOlderRowsArriveOutOfChronologicalOrder() = runBlocking {
        val day = LocalDate.of(2026, 11, 2)
        val stamped = Instant.parse("2026-11-02T03:00:00Z").epochSecond
        val older = Instant.parse("2026-10-01T03:00:00Z").epochSecond
        val comments = Comments(
            List(100) { index -> comment("old-$index", older + index, "旧评论") } +
                comment("current", stamped, "日期内的评论"),
        )
        withMockAi(listOf(oneIssue)) { connection, _, payload ->
            val snapshot = BriefingSync(commentRepository = comments, feedbackRepository = Feedback())
                .update(connection, BriefingSnapshot(), BriefingDateFilter.Custom(day, day)) {}

            assertEquals(listOf(0 to 100, 100 to 100), comments.requests)
            assertEquals(1, snapshot.commentCount)
            assertTrue(payload.get().contains("日期内的评论"))
            assertFalse(payload.get().contains("旧评论"))
        }
    }

    @Test
    fun retriesWithTheOriginalPageSizeWhenALargePageIsRejected() = runBlocking {
        val stamped = Instant.now().minusSeconds(60).epochSecond
        val comments = Comments { _, span ->
            if (span == 100) NetworkState.Error("列表请求失败")
            else NetworkState.Success(CommentListVO(1, listOf(comment("one", stamped, ""))))
        }
        val connection = AiConnection("http://127.0.0.1:1/v1", "unused", "")
        val snapshot = BriefingSync(commentRepository = comments, feedbackRepository = Feedback())
            .update(connection, BriefingSnapshot(), BriefingDateFilter.LastDay) {}

        assertEquals(listOf(0 to 100, 0 to 20), comments.requests)
        assertEquals(1, snapshot.commentCount)
        assertTrue(snapshot.sourceWarnings.isEmpty())
    }

    @Test
    fun customDateIncludesBothDayBoundariesAndSearchesBeyondFirstTwoHundredRows() = runBlocking {
        val firstDay = LocalDate.of(2026, 11, 2)
        val lastDay = firstDay.plusDays(1)
        val filter = BriefingDateFilter.Custom(firstDay, lastDay)
        val start = Instant.parse("2026-11-01T16:00:00Z").epochSecond
        val end = Instant.parse("2026-11-03T16:00:00Z").epochSecond
        val comments = Comments(
            List(200) { comment("new-$it", end, "后续评论") } + listOf(
                comment("c-start", start, "起始当天的评论"),
                comment("c-before", start - 1, "筛选前的评论"),
                comment("c-undated", 0, "日期缺失的评论"),
            ),
        )
        val feedback = Feedback(NetworkState.Success(FeedbackVO(
            listOf(
                FeedbackData(id = "f-end", iid = "42", createTime = end * 1000 - 1, content = "结束当天的反馈"),
                FeedbackData(id = "f-after", iid = "42", createTime = end, content = "筛选后的反馈"),
            ),
            2,
        )))
        val review = """{"items":[{"kind":"ISSUE","title":"测试问题","ids":["F1","F2"]}]}"""
        withMockAi(listOf(review)) { connection, _, payload ->
            val snapshot = BriefingSync(commentRepository = comments, feedbackRepository = feedback)
                .update(connection, BriefingSnapshot(), filter) {}

            assertEquals((0..200 step 100).map { it to 100 }, comments.requests)
            assertEquals(1, snapshot.commentCount)
            assertEquals(1, snapshot.feedbackCount)
            assertFalse(snapshot.capped)
            assertEquals("2026-11-02 至 2026-11-03", snapshot.windowLabel)
            assertEquals(2, snapshot.works.getValue("id:42").topics.single().records)
            assertTrue(payload.get().contains("起始当天的评论"))
            assertTrue(payload.get().contains("结束当天的反馈"))
            assertFalse(payload.get().contains("筛选前的评论"))
            assertFalse(payload.get().contains("筛选后的反馈"))
        }
    }

    @Test
    fun reviewsMoreThanOneThousandRecordsAcrossAllProjectsAndRetriesTheFailedPage() = runBlocking {
        val stamp = Instant.now().minusSeconds(60).epochSecond
        val rows = List(1000) { index ->
            comment("c-$index", stamp, "联机掉线 $index", (index / 10 + 1).toString())
        } + comment("c-last", stamp, "最后一个项目的报告", "101")
        var firstAttempt = true
        val comments = Comments { offset, span ->
            if (offset == 200 && firstAttempt) {
                firstAttempt = false
                NetworkState.Error("连接断开", IOException())
            } else {
                NetworkState.Success(CommentListVO(rows.size, rows.drop(offset).take(span)))
            }
        }
        val ids = (1..10).joinToString(",") { "\"F$it\"" }
        val projectReview = """{"items":[{"kind":"ISSUE","title":"联机掉线","ids":[$ids]}]}"""
        withMockAi(List(100) { projectReview } + oneIssue) { connection, requests, payload ->
            val snapshot = BriefingSync(commentRepository = comments, feedbackRepository = Feedback())
                .update(connection, BriefingSnapshot(), BriefingDateFilter.LastDay) {}

            assertEquals(1001, snapshot.commentCount)
            assertEquals(101, snapshot.works.size)
            assertEquals(101, requests.get())
            assertEquals(1001, snapshot.works.values.sumOf { it.topics.single().records })
            assertEquals("COMMENT:c-last", snapshot.works.getValue("id:101").topics.single().originalReferences.single().key)
            assertTrue(payload.get().contains("最后一个项目的报告"))
            assertEquals(2, comments.requests.count { it.first == 200 })
            assertEquals(1000 to 100, comments.requests.last())
            assertFalse(snapshot.capped)
            assertTrue(snapshot.sourceWarnings.isEmpty())
        }
    }

    @Test
    fun readsFourPagesConcurrentlyAndKeepsEveryRecordInOffsetOrder() = runBlocking {
        val stamp = Instant.now().minusSeconds(60).epochSecond
        val rows = List(501) { comment("c-$it", stamp, "") }
        val gates = (100..400 step 100).associateWith { CompletableDeferred<Unit>() }
        val finished = mutableListOf<Int>()
        var active = 0
        var peak = 0
        val comments = Comments { offset, span ->
            active++
            peak = maxOf(peak, active)
            try {
                if (active == 4) gates.getValue(400).complete(Unit)
                gates[offset]?.await()
                finished += offset
                gates[offset - 100]?.complete(Unit)
                NetworkState.Success(CommentListVO(rows.size, rows.drop(offset).take(span)))
            } finally {
                active--
            }
        }
        val connection = AiConnection("http://127.0.0.1:1/v1", "unused", "")
        val progress = mutableListOf<String>()
        val snapshot = withTimeout(5_000) {
            BriefingSync(commentRepository = comments, feedbackRepository = Feedback())
                .update(connection, BriefingSnapshot(), BriefingDateFilter.LastDay, progress::add)
        }
        assertEquals(4, peak)
        assertEquals(listOf(0, 400, 300, 200, 100, 500), finished)
        assertEquals(501, snapshot.commentCount)
        assertEquals(((100..500 step 100).toList() + 501).map { "正在筛选评论 · 已检查 $it 条 · 日期内 $it 条…" },
            progress.filter { it.startsWith("正在筛选评论") })
    }

    @Test
    fun restartsFromTheActualOffsetWhenAPrefetchedPageReturnsFewerRows() = runBlocking {
        val stamp = Instant.now().minusSeconds(60).epochSecond
        val rows = List(401) { comment("c-$it", stamp, "") }
        val comments = Comments { offset, span ->
            val requestedRows = if (offset == 100) 62 else span
            NetworkState.Success(CommentListVO(rows.size, rows.drop(offset).take(requestedRows)))
        }
        val connection = AiConnection("http://127.0.0.1:1/v1", "unused", "")
        val snapshot = BriefingSync(commentRepository = comments, feedbackRepository = Feedback())
            .update(connection, BriefingSnapshot(), BriefingDateFilter.LastDay) {}

        assertEquals(401, snapshot.commentCount)
        assertTrue(162 to 100 in comments.requests)
        assertTrue(262 to 100 in comments.requests)
        assertTrue(362 to 100 in comments.requests)
    }

    @Test
    fun retriesFromTheFailedPageWhenTheServerRejectsAPrefetch() = runBlocking {
        val stamp = Instant.now().minusSeconds(60).epochSecond
        val rows = List(401) { comment("c-$it", stamp, "") }
        var rejected = false
        val comments = Comments { offset, span ->
            if (offset == 100 && !rejected) {
                rejected = true
                NetworkState.Error("请求过于频繁")
            } else {
                NetworkState.Success(CommentListVO(rows.size, rows.drop(offset).take(span)))
            }
        }
        val connection = AiConnection("http://127.0.0.1:1/v1", "unused", "")
        val snapshot = BriefingSync(commentRepository = comments, feedbackRepository = Feedback())
            .update(connection, BriefingSnapshot(), BriefingDateFilter.LastDay) {}

        assertEquals(401, snapshot.commentCount)
        assertEquals(2, comments.requests.count { it.first == 100 })
        assertEquals(listOf(100, 200, 300, 400), comments.requests.takeLast(4).map { it.first })
        assertTrue(snapshot.sourceWarnings.isEmpty())
    }

    @Test
    fun producesFeedbackBriefingWhenNeteaseRejectsComments() = runBlocking {
        val comments = Comments(NetworkState.Error("服务器异常"))
        val feedback = Feedback(NetworkState.Success(FeedbackVO(
            listOf(FeedbackData(
                id = "f1", iid = "42", resName = "测试作品", content = "联机掉线",
                createTime = Instant.now().minusSeconds(60).epochSecond,
                picList = listOf(
                    " https://images.example.test/screen.png?token=local ",
                    "//images.example.test/screen.png?token=local",
                    "file:///C:/example.png",
                    "https://127.0.0.1/example.png",
                ),
            )),
            1,
        )))

        withMockAi(listOf(oneIssue)) { connection, _, payload ->
            val result = BriefingSync(commentRepository = comments, feedbackRepository = feedback)
                .update(connection, BriefingSnapshot(), BriefingDateFilter.LastDay) {}

            assertEquals(1, result.feedbackCount)
            assertEquals(setOf(Source.COMMENT), result.sourceWarnings.keys)
            assertTrue(result.sourceWarnings.getValue(Source.COMMENT).contains("服务器异常"))
            assertEquals("联机断开连接", result.works.getValue("id:42").topics.single().title)
            assertEquals(
                listOf("https://images.example.test/screen.png?token=local"),
                result.works.getValue("id:42").topics.single().imageUrls,
            )
            assertTrue(payload.get().contains("玩家反馈"))
            assertFalse(payload.get().contains("images.example.test"))
            assertEquals(result, briefingJson.decodeFromString<BriefingSnapshot>(briefingJson.encodeToString(result)))
        }
    }

    @Test
    fun retainsLastReportWhenNeitherSourceCanBeRead() = runBlocking {
        val connection = AiConnection("http://127.0.0.1:1/v1", "unused", "")
        val status = mutableListOf<String>()
        val error = assertFailsWith<BriefingFailure> {
            BriefingSync(
                commentRepository = Comments(NetworkState.Error("服务器异常")),
                feedbackRepository = Feedback(NetworkState.Error("服务器异常")),
            ).update(connection, BriefingSnapshot(updatedAt = 100), BriefingDateFilter.LastDay, status::add)
        }
        assertTrue(error.message.orEmpty().contains("评论读取失败"))
        assertTrue(error.message.orEmpty().contains("玩家反馈读取失败"))
        assertEquals(
            listOf(
                "正在读取评论和玩家反馈…",
                "正在按时间读取评论…",
                "正在按时间读取玩家反馈…",
            ),
            status,
        )
    }

    @Test
    fun sendsExpiredSessionBackToLogin() = runBlocking {
        val connection = AiConnection("http://127.0.0.1:1/v1", "unused", "")
        assertFailsWith<BriefingSessionExpired> {
            BriefingSync(
                commentRepository = Comments(NetworkState.Error("登录过期", CookiesExpiredException())),
                feedbackRepository = Feedback(),
            ).update(connection, BriefingSnapshot(), BriefingDateFilter.LastDay) {}
        }
        Unit
    }

    @Test
    fun excludesAlreadyAnsweredAndForbiddenFeedbackFromReplyTargets() = runBlocking {
        val timestamp = Instant.now().minusSeconds(60).epochSecond
        val feedback = Feedback(NetworkState.Success(FeedbackVO(
            listOf(
                FeedbackData(id = "f1", iid = "42", content = "黑屏", createTime = timestamp),
                FeedbackData(id = "f2", iid = "42", content = "黑屏", createTime = timestamp, forbidReply = true),
                FeedbackData(id = "f3", iid = "42", content = "黑屏", createTime = timestamp, reply = "已记录"),
            ),
            3,
        )))
        val result = """{"items":[{"kind":"ISSUE","title":"黑屏","ids":["F1","F2","F3"]}]}"""
        withMockAi(listOf(result)) { connection, _, _ ->
            val snapshot = BriefingSync(
                commentRepository = Comments(NetworkState.Success(CommentListVO(0, emptyList()))),
                feedbackRepository = feedback,
            ).update(connection, BriefingSnapshot(), BriefingDateFilter.LastDay) {}
            val topic = snapshot.works.getValue("id:42").topics.single()
            assertEquals(3, topic.records)
            assertEquals(listOf("f1"), topic.replyTargets.map(ReplyTarget::id))
            assertEquals(listOf("FEEDBACK:f1", "FEEDBACK:f2", "FEEDBACK:f3"), topic.originalReferences.map { it.key })
        }
    }

    @Test
    fun readsOnlyReferencedOriginalsForTheWorkAfterRestart() = runBlocking {
        val body = "  第一行\n第二行  "
        val older = Instant.now().minus(8, ChronoUnit.DAYS).epochSecond
        val preceding = List(200) { comment("other-$it", older, "其他反馈") }
        val matching = listOf(comment("same", older, body), comment("wrong-work", older, "另一个作品", "77"))
        val comments = Comments(preceding + matching)
        val feedback = Feedback(NetworkState.Success(FeedbackVO(
            listOf(FeedbackData(id = "same", iid = "42", content = "已回复的原文", reply = "收到")),
            1,
        )))
        val saved = BriefingTopic(
            TopicKind.ISSUE, "整理后的标题", "", 3, 3, false,
            originalReferences = listOf(
                OriginalReference(Source.COMMENT, "COMMENT:same"),
                OriginalReference(Source.FEEDBACK, "FEEDBACK:same"),
                OriginalReference(Source.COMMENT, "COMMENT:wrong-work"),
            ),
        )
        val topic = briefingJson.decodeFromString<BriefingTopic>(briefingJson.encodeToString(saved))
        val sync = BriefingSync(commentRepository = comments, feedbackRepository = feedback)
        val originals = sync.loadOriginals("id:42", topic)
        assertEquals((0..200 step 20).map { it to 20 }, comments.requests)
        assertEquals(listOf(Source.COMMENT, Source.FEEDBACK), originals.entries.map { it.source })
        assertEquals(listOf(body, "已回复的原文"), originals.entries.map { it.text })
        assertEquals(1, originals.missing)
        assertTrue(originals.warnings.isEmpty())
    }

    @Test
    fun reusesOnlyCompleteLegacyReferences() = runBlocking {
        val comments = Comments(NetworkState.Success(CommentListVO(1, listOf(comment("a", 0, "旧评论")))))
        val sync = BriefingSync(commentRepository = comments, feedbackRepository = Feedback())
        val old = BriefingTopic(
            TopicKind.ISSUE, "旧简报", "", 2, 2, false,
            replyTargets = listOf(ReplyTarget(Source.COMMENT, "a", 0)),
        )
        assertFailsWith<BriefingFailure> { sync.loadOriginals("id:42", old) }
        assertTrue(comments.requests.isEmpty())
        val originals = sync.loadOriginals("id:42", old.copy(records = 1))
        assertEquals("旧评论", originals.entries.single().text)
    }

    private fun comment(id: String, time: Long, body: String, workId: String = "42") = CommentData(
        id, "", workId, "", time, "测试作品", "4", "7", body,
    )

    private class Comments(
        private val page: suspend (Int, Int) -> NetworkState<CommentListVO>,
    ) : CommentRepository {
        constructor(result: NetworkState<CommentListVO>) : this(page = { _, _ -> result })
        constructor(entries: List<CommentData>) : this(page = { offset, span ->
            NetworkState.Success(CommentListVO(entries.size, entries.drop(offset).take(span)))
        })

        val requests = mutableListOf<Pair<Int, Int>>()

        override suspend fun getCommentList(
            start: Int,
            span: Int,
            key: String?,
            tag: String?,
            startDate: String?,
            endDate: String?,
        ): NetworkState<CommentListVO> {
            assertEquals(listOf(null, null, null, null), listOf(key, tag, startDate, endDate))
            requests += start to span
            return page(start, span)
        }

        override suspend fun replyComment(commentId: String, content: ReplyDTO): NetworkState<Unit> =
            error("同步不得回复评论")
    }

    private class Feedback(
        private val result: NetworkState<FeedbackVO> = NetworkState.Success(FeedbackVO(emptyList(), 0)),
    ) : FeedbackRepository {
        override suspend fun loadFeedback(
            from: Int,
            size: Int,
            status: String?,
            key: String?,
        ): NetworkState<FeedbackVO> {
            assertEquals(0, from)
            assertTrue(size == 20 || size == 100)
            assertEquals(null, status)
            assertEquals(null, key)
            return result
        }

        override suspend fun sendReply(feedbackId: String, content: ReplyDTO): NetworkState<NoNeedData> =
            error("同步不得回复反馈")

        override suspend fun submitFeedback(feedback: DeveloperFeedbackDTO): NetworkState<DeveloperFeedbackVO> =
            error("同步不得提交反馈")
    }

    private companion object {
        const val oneIssue = """{"items":[{"kind":"ISSUE","title":"联机断开连接","ids":["F1"]}]}"""
    }
}
