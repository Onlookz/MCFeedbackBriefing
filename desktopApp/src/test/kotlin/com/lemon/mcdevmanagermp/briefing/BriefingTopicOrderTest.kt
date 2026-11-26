package com.lemon.mcdevmanagermp.briefing

import kotlin.test.Test
import kotlin.test.assertEquals

class BriefingTopicOrderTest {
    @Test
    fun sortsCardsByChosenTimeOrVolumeAndKeepsUndatedCardsLastForTimeOrders() {
        val repeat = topic("多次报告", first = 1_000, latest = 4_000, records = 6)
        val recent = topic("最近提交", first = 5_000, latest = 5_000, records = 2)
        val middle = topic("中间提交", first = 3_000, latest = 3_000, records = 1)
        val undated = topic("时间未知", first = null, latest = null, records = 4)
        val topics = listOf(undated, recent, repeat, middle)

        assertEquals(topics, BriefingTopicOrder.CATEGORY.apply(topics))
        assertEquals(listOf(recent, repeat, middle, undated), BriefingTopicOrder.NEWEST.apply(topics))
        assertEquals(listOf(repeat, middle, recent, undated), BriefingTopicOrder.OLDEST.apply(topics))
        assertEquals(listOf(repeat, undated, recent, middle), BriefingTopicOrder.MOST_REPORTED.apply(topics))
    }

    private fun topic(title: String, first: Long?, latest: Long?, records: Int) =
        BriefingTopic(
            TopicKind.ISSUE, title, "", records, 1, false,
            firstFeedbackAt = first,
            latestFeedbackAt = latest,
        )
}
