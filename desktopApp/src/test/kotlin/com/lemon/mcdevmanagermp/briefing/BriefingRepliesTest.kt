package com.lemon.mcdevmanagermp.briefing

import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.consts.CookiesExpiredException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BriefingRepliesTest {
    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun automaticRepliesUseTheEnabledCategoryForNewFeedbackOnce() {
        val issue = target("issue", 2000)
        val suggestion = target("suggestion", 2000)
        val historical = target("old", 999)
        val comment = ReplyTarget(Source.COMMENT, "comment", 2000)
        val report = BriefingSnapshot(works = mapOf("42" to WorkBriefing("作品", "", listOf(
            topic(TopicKind.ISSUE, issue, historical, comment),
            topic(TopicKind.SUGGESTION, issue, suggestion),
            topic(TopicKind.OBSERVE, target("vague", 2000)),
        ))))
        assertTrue(report.autoReplies(ReplyState()).isEmpty())
        val settings = ReplySettings(
            issue = ReplyRule("问题话术", enabledSince = 1000),
            suggestion = ReplyRule("建议话术", enabledSince = 1000),
        )
        val planned = report.autoReplies(ReplyState(settings))
        assertEquals(listOf(issue, suggestion), planned.map { it.target })
        assertEquals(listOf("问题话术", "建议话术"), planned.map { it.text })
        assertEquals(
            listOf(suggestion),
            report.autoReplies(ReplyState(settings, mapOf(issue.key to ReplyMark.SENT))).map { it.target },
        )
    }

    @Test
    fun reservesBeforeSendingAndDeduplicatesAcrossRestarts() = runBlocking {
        val store = newStore()
        val target = ReplyTarget(Source.COMMENT, "c1", 2000)
        var writes = 0
        val sender = BriefingReplies(store) { item, content ->
            assertEquals(target, item)
            assertEquals("已记录", content)
            assertEquals(ReplyMark.UNCONFIRMED, store.load().attempts[target.key])
            writes++
            NetworkState.Success(Unit)
        }
        val request = ReplyRequest(target, "已记录")
        assertEquals(1, sender.send(listOf(request, request)).sent)
        val reopened = BriefingReplies(newStore()) { _, _ -> error("已有发送记录，不应重发") }
        assertEquals(0, reopened.send(listOf(request)).sent)
        assertEquals(1, writes)
        assertEquals(ReplyMark.SENT, reopened.load().attempts[target.key])
    }

    @Test
    fun preservesUncertainAttemptAndLeavesOtherFeedbackPending() = runBlocking {
        val first = target("first", 2000)
        val second = target("second", 3000)
        val calls = mutableListOf<ReplyTarget>()
        val service = BriefingReplies(newStore()) { item, _ ->
            calls += item
            if (item == first) NetworkState.Error<Unit>("超时") else NetworkState.Success(Unit)
        }
        val requests = listOf(first, second).map { ReplyRequest(it, "已记录") }
        val outcome = service.send(requests)
        assertTrue(outcome.unconfirmed)
        assertEquals(1, outcome.remaining)
        assertEquals(mapOf(first.key to ReplyMark.UNCONFIRMED), outcome.state.attempts)
        assertEquals(1, service.send(requests).sent)
        assertEquals(listOf(first, second), calls)
    }

    @Test
    fun releasesAnAttemptWhenNeteaseConfirmsLoginExpired() = runBlocking {
        val request = ReplyRequest(target("expired", 2000), "已记录")
        val store = newStore()
        val service = BriefingReplies(store) { _, _ -> NetworkState.Error<Unit>("会话过期", CookiesExpiredException()) }
        val outcome = service.send(listOf(request))
        assertTrue(outcome.sessionExpired)
        assertFalse(outcome.unconfirmed)
        assertEquals(1, outcome.remaining)
        assertTrue(store.load().attempts.isEmpty())
    }

    @Test
    fun honorsALaterDisableBeforeAnAutomaticSend() = runBlocking {
        val store = newStore()
        val settings = ReplySettings(issue = ReplyRule("问题话术", enabledSince = 1000))
        store.saveSettings(settings)
        val target = target("rule-changed", 2000)
        val auto = ReplyRequest(target, "问题话术", ReplyCategory.ISSUE)
        store.saveSettings(ReplySettings())
        val service = BriefingReplies(store) { _, _ -> error("停用后不得发自动回复") }
        assertEquals(0, service.send(listOf(auto)).sent)
        assertTrue(store.load().attempts.isEmpty())
    }

    private fun newStore() = ReplyStore(9, temp.root.toPath())

    private fun target(id: String, time: Long) = ReplyTarget(Source.FEEDBACK, id, time)

    private fun topic(kind: TopicKind, vararg targets: ReplyTarget) =
        BriefingTopic(kind, "问题", "", targets.size, targets.size, false, replyTargets = targets.toList())
}
