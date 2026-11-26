package com.lemon.mcdevmanagermp.briefing

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiBriefingTest {
    @Test
    fun producesNeutralSummaryAndCountsDistinctPlayersAcrossSources() = runBlocking {
        val reply = """
            {"items":[
                {"kind":"ISSUE","title":"多人联机取物后丢失","trigger":"联机时从桶内取出物品","ids":["F1","F2","F4"]},
                {"kind":"OBSERVE","title":"不应直接显示的原话","ids":["F5","F6"]},
                {"kind":"SUGGESTION","title":"垃圾作者，赶紧去死","ids":["F7"]}
            ]}
        """.trimIndent()
        withMockAi(listOf(reply)) { connection, requests, lastRequest ->
            val sourceRows = listOf(
                record(Source.COMMENT, "c1", "private-user-1", "破作者！联机取桶会吃物品，玩家邮箱 a@example.com"),
                record(Source.COMMENT, "c2", "private-user-1", "这个问题只在联机遇到"),
                record(Source.COMMENT, "c3", "private-user-2", "纯辱骂内容"),
                record(Source.FEEDBACK, "f1", "private-user-2", "联机取物后物品丢失"),
                record(Source.FEEDBACK, "f2", "private-user-1", "不能用"),
                record(Source.FEEDBACK, "f3", "private-user-3", "出问题了"),
                record(Source.FEEDBACK, "f4", "private-user-4", "恶意请求 Authorization: Bearer private-test-token"),
            )
            val ai = AiBriefing()
            val processed = ai.review(connection, sourceRows, emptyMap())
            val topics = processed.getValue("id:98").topics
            assertEquals(listOf(TopicKind.ISSUE, TopicKind.OBSERVE), topics.map { it.kind })
            assertEquals("多人联机取物后丢失", topics[0].title)
            assertEquals(3, topics[0].records)
            assertEquals(2, topics[0].players)
            assertEquals("缺少复现信息的异常反馈", topics[1].title)
            val payload = lastRequest.get()
            assertTrue(payload.contains("［已隐去］"))
            assertFalse(payload.contains("a@example.com"))
            assertFalse(payload.contains("private-user"))
            assertFalse(payload.contains("private-test-token"))
            assertFalse(briefingJson.encodeToString(processed).contains("破作者"))

            val cached = ai.review(connection, sourceRows, processed)
            assertEquals(processed, cached)
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun rejectsAnInventedReference() = runBlocking {
        val invented = """{"items":[{"kind":"ISSUE","title":"失真的结果","ids":["F2"]}]}"""
        withMockAi(listOf(invented, invented)) { connection, _, _ ->
            assertFailsWith<BriefingFailure> {
                AiBriefing().review(connection, listOf(record(Source.FEEDBACK, "f1", "1", "真实数据")), emptyMap())
            }
        }
    }

    @Test
    fun sharesValidFeedbackAcrossTopicsAndIgnoresUnknownReferences() = runBlocking {
        val reply = """
            {"items":[
                {"kind":"ISSUE","title":"联机取物丢失","ids":["F1","F1","F99",2]},
                {"kind":"SUGGESTION","title":"添加取物提示","ids":["F1"]},
                {"kind":"ISSUE","title":"无依据的条目","ids":["F99"]}
            ]}
        """.trimIndent()
        withMockAi(listOf(reply)) { connection, requests, _ ->
            val entry = record(Source.FEEDBACK, "f1", "1", "联机取物丢失，建议添加取物提示")
            val topics = AiBriefing().review(connection, listOf(entry), emptyMap()).getValue("id:98").topics
            assertEquals(listOf("联机取物丢失", "添加取物提示"), topics.map { it.title })
            assertTrue(topics.all { it.players == 1 && it.records == 1 })
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun retriesAnEntireBatchWithUnknownReferencesOnce() = runBlocking {
        val missing = """{"items":[{"kind":"ISSUE","title":"报告了故障","ids":["F99"]}]}"""
        val corrected = """{"items":[{"kind":"ISSUE","title":"联机断开连接","ids":[" f1 "]}]}"""
        withMockAi(listOf(missing, corrected)) { connection, requests, _ ->
            val entry = record(Source.FEEDBACK, "f1", "1", "联机后断开连接")
            val topic = AiBriefing().review(connection, listOf(entry), emptyMap()).getValue("id:98").topics.single()
            assertEquals("联机断开连接", topic.title)
            assertEquals(2, requests.get())
        }
    }

    @Test
    fun mergesTheSameIssueAcrossModelBatches() = runBlocking {
        val replies = listOf(
            """{"items":[{"kind":"ISSUE","title":"容器物品丢失","ids":["F1","F4"]},{"kind":"SUGGESTION","title":"添加取物提示","ids":["F7"]}]}""",
            """{"items":[{"kind":"ISSUE","title":"取物时道具消失","ids":["F31"]}]}""",
            """{"items":[{"kind":"ISSUE","title":"从容器取物后物品丢失","ids":["T1","T3"]}]}""",
        )
        withMockAi(replies) { connection, requests, _ ->
            val entries = (0..30).map { index ->
                val id = index.toString().padStart(2, '0')
                val submitted = 1_700_000_000L + index
                record(Source.COMMENT, id, "uid-$index", "反馈 $index")
                    .copy(time = if (index == 30) submitted * 1000 else submitted)
            }
            val progress = mutableListOf<String>()
            val topics = AiBriefing().review(connection, entries, emptyMap(), progress::add).getValue("id:98").topics
            assertEquals(
                listOf("AI 整理 · 作品 1/1 · 第 1/2 批（30 条）", "AI 整理 · 作品 1/1 · 第 2/2 批（1 条）",
                    "AI 整理 · 作品 1/1 · 正在合并重复问题"),
                progress,
            )
            assertEquals(3, requests.get())
            assertEquals(2, topics.size)
            assertEquals(3, topics.first().players)
            assertEquals(3, topics.first().records)
            assertEquals(1_700_000_000_000L, topics.first().firstFeedbackAt)
            assertEquals(1_700_000_030_000L, topics.first().latestFeedbackAt)
            assertEquals("添加取物提示", topics.last().title)
            assertEquals(1, topics.last().records)
        }
    }

    @Test
    fun timesOutWhenProviderSendsHeadersButKeepsTheResponseOpen() = runBlocking {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.use {
                exchange.requestBody.readAllBytes()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(' '.code)
                exchange.responseBody.flush()
                responseStarted.countDown()
                releaseResponse.await(5, TimeUnit.SECONDS)
            }
        }
        server.start()
        try {
            val port = server.address.port
            val connection = AiConnection("http://127.0.0.1:$port/v1", "test-model", "")
            val failure = assertFailsWith<BriefingFailure> {
                AiBriefing(requestTimeoutMillis = 1000).review(
                    connection, listOf(record(Source.FEEDBACK, "f1", "1", "连接后黑屏")), emptyMap(),
                )
            }
            assertEquals(0L, responseStarted.count)
            assertTrue(failure.message.orEmpty().contains("未返回完整结果"))
        } finally {
            releaseResponse.countDown()
            server.stop(0)
        }
    }

    @Test
    fun deepSeekFlashRequiresAKeyBeforeFetchingPlayerData() {
        val base = "https://api.deepseek.com"
        val error = assertFailsWith<BriefingFailure> { AiConnection(base, "deepseek-flash", "").endpoint() }
        assertTrue(error.message.orEmpty().contains("DeepSeek API Key"))
        assertEquals("$base/chat/completions", AiConnection(base, "deepseek-flash", "test-key").endpoint().toString())
    }

    @Test
    fun restoresAttachmentReferencesForOldBriefingsAndReusesTextForNewUrls() = runBlocking {
        val reply = """{"items":[{"kind":"ISSUE","title":"进入房间后黑屏","ids":["F1"]}]}"""
        withMockAi(listOf(reply, reply)) { connection, requests, payload ->
            val ai = AiBriefing()
            val primary = record(Source.FEEDBACK, "first", "1", "进入房间后黑屏")
            val unrelated = record(Source.FEEDBACK, "other", "2", "感谢更新")
            val old = ai.review(connection, listOf(primary, unrelated), emptyMap()).mapValues { (_, work) ->
                work.copy(topics = work.topics.map { it.copy(sourceIndexes = emptyList()) })
            }
            val firstUrl = "https://images.example.test/room.png?token=old"
            val unrelatedUrl = "https://images.example.test/unrelated.png"
            val withPictures = ai.review(connection, listOf(
                primary.copy(imageUrls = listOf(firstUrl, firstUrl)),
                unrelated.copy(imageUrls = listOf(unrelatedUrl)),
            ), old)
            assertEquals(listOf(firstUrl), withPictures.getValue("id:98").topics.single().imageUrls)

            val renewedUrl = "https://images.example.test/room.png?token=new"
            val refreshed = ai.review(connection, listOf(
                primary.copy(imageUrls = listOf(renewedUrl)), unrelated,
            ), withPictures)
            assertEquals(listOf(renewedUrl), refreshed.getValue("id:98").topics.single().imageUrls)
            assertEquals(2, requests.get())
            assertFalse(payload.get().contains("images.example.test"))
        }
    }

    @Test
    fun keepsFeedbackWithOnlyAnImageWithoutCallingAi() = runBlocking {
        val connection = AiConnection("http://127.0.0.1:1/v1", "unused", "")
        val image = "https://images.example.test/room.png"
        val work = AiBriefing().review(connection, listOf(
            record(Source.FEEDBACK, "f1", "1", "").copy(imageUrls = listOf(image)),
        ), emptyMap()).getValue("id:98")
        assertTrue(work.topics.isEmpty())
        assertEquals("仅附截图的反馈", work.imageOnlyFeedback?.title)
        assertEquals(listOf(image), work.imageOnlyFeedback?.imageUrls)
    }

    private fun record(source: Source, id: String, author: String, text: String): PlayerEntry =
        PlayerEntry(source, id, "98", "测试模组", author, 0, text)
}

internal suspend fun withMockAi(
    replies: List<String>,
    block: suspend (AiConnection, AtomicInteger, AtomicReference<String>) -> Unit,
) {
    val requestCount = AtomicInteger()
    val lastRequest = AtomicReference("")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/chat/completions") { request ->
        request.use {
            val content = replies[requestCount.getAndIncrement()]
            val body = request.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val messages = briefingJson.parseToJsonElement(body).jsonObject.getValue("messages") as JsonArray
            lastRequest.set(messages[1].jsonObject.getValue("content").jsonPrimitive.content)
            val modelMessage = JsonObject(mapOf("content" to JsonPrimitive(content)))
            val choice = JsonObject(mapOf("message" to modelMessage))
            val response = JsonObject(mapOf("choices" to JsonArray(listOf(choice))))
            val bytes = response.toString().toByteArray(Charsets.UTF_8)
            request.sendResponseHeaders(200, bytes.size.toLong())
            request.responseBody.write(bytes)
        }
    }
    server.start()
    try {
        val port = server.address.port
        block(AiConnection("http://127.0.0.1:$port/v1", "test-model", ""), requestCount, lastRequest)
    } finally {
        server.stop(0)
    }
}
