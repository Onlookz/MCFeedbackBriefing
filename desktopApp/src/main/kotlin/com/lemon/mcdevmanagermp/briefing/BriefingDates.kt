package com.lemon.mcdevmanagermp.briefing

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal sealed interface BriefingDateFilter {
    val label: String

    fun windowAt(now: Instant): BriefingWindow

    data object LastDay : BriefingDateFilter {
        override val label = "最近 1 天"

        override fun windowAt(now: Instant): BriefingWindow =
            BriefingWindow(now.minus(1, ChronoUnit.DAYS).toEpochMilli(), now.toEpochMilli(), fullDays = false)
    }

    data class Custom(val start: LocalDate, val end: LocalDate) : BriefingDateFilter {
        init {
            require(start <= end)
        }

        override val label: String get() = if (start == end) start.toString() else "$start 至 $end"

        override fun windowAt(now: Instant): BriefingWindow = BriefingWindow(
            start.atStartOfDay(briefingZone).toInstant().toEpochMilli(),
            end.plusDays(1).atStartOfDay(briefingZone).toInstant().toEpochMilli(),
            fullDays = true,
        )
    }
}

private val rangeTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(briefingZone)
private val rangeDateFormat = DateTimeFormatter.ISO_LOCAL_DATE.withZone(briefingZone)

@Serializable
internal data class BriefingWindow(val startAt: Long, val endBefore: Long, val fullDays: Boolean) {
    operator fun contains(publishedAt: Long): Boolean =
        publishedAt > 0 && publishedAt >= startAt && publishedAt < endBefore

    val label: String
        get() {
            val format = if (fullDays) rangeDateFormat else rangeTimeFormat
            val last = if (fullDays) endBefore - 1 else endBefore
            val startLabel = format.format(Instant.ofEpochMilli(startAt))
            val endLabel = format.format(Instant.ofEpochMilli(last))
            return if (startLabel == endLabel) startLabel else "$startLabel 至 $endLabel"
        }
}
