package com.lemon.mcdevmanagermp.briefing

import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.consts.CookiesExpiredException
import com.lemon.mcdevmanagermp.data.consts.LoginException
import com.lemon.mcdevmanagermp.data.repository.CommentRepositoryImpl
import com.lemon.mcdevmanagermp.data.repository.FeedbackRepositoryImpl
import com.lemon.mcdevmanagermp.domain.comment.CommentUseCase
import com.lemon.mcdevmanagermp.domain.feedback.FeedbackUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

@Serializable
internal enum class ReplyCategory(val label: String, val defaultText: String) {
    ISSUE("反馈问题", "谢谢你的反馈。问题已记录，我们会结合描述排查。"),
    SUGGESTION("提供建议", "谢谢你的建议。我们已经记录，会在后续调整玩法时参考。");

    companion object {
        fun forTopic(kind: TopicKind): ReplyCategory = when (kind) {
            TopicKind.ISSUE, TopicKind.OBSERVE -> ISSUE
            TopicKind.SUGGESTION -> SUGGESTION
        }
    }
}

@Serializable
internal data class ReplyTarget(val source: Source, val id: String, val publishedAt: Long) {
    val key: String get() = "${source.name}:$id"
}

@Serializable
internal data class ReplyRule(val text: String, val enabledSince: Long? = null)

@Serializable
internal data class ReplySettings(
    val issue: ReplyRule = ReplyRule(ReplyCategory.ISSUE.defaultText),
    val suggestion: ReplyRule = ReplyRule(ReplyCategory.SUGGESTION.defaultText),
) {
    fun rule(category: ReplyCategory): ReplyRule = when (category) {
        ReplyCategory.ISSUE -> issue
        ReplyCategory.SUGGESTION -> suggestion
    }
}

@Serializable
internal enum class ReplyMark { UNCONFIRMED, SENT }

@Serializable
internal data class ReplyState(
    val settings: ReplySettings = ReplySettings(),
    val attempts: Map<String, ReplyMark> = emptyMap(),
)

internal data class ReplyRequest(
    val target: ReplyTarget,
    val text: String,
    val automaticCategory: ReplyCategory? = null,
)

internal data class ReplyOutcome(
    val state: ReplyState,
    val sent: Int,
    val remaining: Int,
    val unconfirmed: Boolean,
    val sessionExpired: Boolean,
) {
    fun message(automatic: Boolean): String = buildString {
        if (automatic) append("自动回复") else append("手动回复")
        append("已发送 $sent 条。")
        if (unconfirmed) append("有 1 条未获得发送确认，已保留记录以免重发。")
        if (remaining > 0) append("还有 $remaining 条待发送。")
    }
}

internal class ReplyStore(
    accountId: Long,
    private val directory: Path = briefingDataDirectory,
) {
    private val stateFile = directory.resolve("replies-$accountId.json")
    private val lockFile = directory.resolve("replies-$accountId.lock")

    fun load(): ReplyState =
        if (Files.notExists(stateFile)) ReplyState() else briefingJson.decodeFromString(Files.readString(stateFile))

    fun saveSettings(settings: ReplySettings): ReplyState =
        change { it.copy(settings = settings) }

    fun reserve(request: ReplyRequest): Boolean {
        val target = request.target
        var reserved = false
        change { current ->
            val stillEnabled = request.automaticCategory?.let { category ->
                val rule = current.settings.rule(category)
                val since = rule.enabledSince
                target.source == Source.FEEDBACK && since != null && target.publishedAt > since && rule.text == request.text
            } ?: true
            if (!stillEnabled || target.key in current.attempts) current
            else {
                reserved = true
                current.copy(attempts = current.attempts + (target.key to ReplyMark.UNCONFIRMED))
            }
        }
        return reserved
    }

    fun markSent(target: ReplyTarget) {
        change { it.copy(attempts = it.attempts + (target.key to ReplyMark.SENT)) }
    }

    fun releaseExpired(target: ReplyTarget) {
        change { it.copy(attempts = it.attempts - target.key) }
    }

    private fun change(transform: (ReplyState) -> ReplyState): ReplyState = synchronized(replyFileLock) {
        Files.createDirectories(directory)
        FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
            channel.lock().use {
                val current = load()
                val next = transform(current)
                if (next != current) writeBriefingFile(stateFile, briefingJson.encodeToString(next))
                next
            }
        }
    }

    private companion object {
        val replyFileLock = Any()
    }
}

internal fun BriefingSnapshot.autoReplies(state: ReplyState): List<ReplyRequest> =
    ReplyCategory.entries.flatMap { category ->
        val rule = state.settings.rule(category)
        val enabledSince = rule.enabledSince ?: return@flatMap emptyList()
        if (rule.text.isBlank()) return@flatMap emptyList()
        works.values.flatMap(WorkBriefing::topics)
            .filter { topic ->
                topic.kind != TopicKind.OBSERVE && ReplyCategory.forTopic(topic.kind) == category
            }
            .flatMap(BriefingTopic::replyTargets)
            .filter { target ->
                target.source == Source.FEEDBACK && target.publishedAt > enabledSince && target.key !in state.attempts
            }
            .map { ReplyRequest(it, rule.text, category) }
    }.distinctBy { it.target.key }

internal class BriefingReplies(
    private val store: ReplyStore,
    private val sendReply: suspend (ReplyTarget, String) -> NetworkState<*> = ::sendNeteaseReply,
) {
    private val sending = Mutex()

    suspend fun load(): ReplyState = withContext(Dispatchers.IO) { store.load() }

    suspend fun saveSettings(settings: ReplySettings): ReplyState =
        withContext(Dispatchers.IO) { store.saveSettings(settings) }

    suspend fun send(requests: List<ReplyRequest>, onProgress: (Int, Int) -> Unit = { _, _ -> }): ReplyOutcome =
        sending.withLock {
            val queued = requests.distinctBy { it.target.key }.map { it.copy(text = it.text.trim()) }
            if (queued.any { it.text.isEmpty() }) throw BriefingFailure("请填写回复内容。")
            var sent = 0
            var sessionExpired = false
            var unconfirmed = false
            var remaining = 0
            for ((index, request) in queued.withIndex()) {
                val target = request.target
                if (!withContext(Dispatchers.IO) { store.reserve(request) }) continue
                onProgress(index + 1, queued.size)
                when (val result = sendReply(target, request.text)) {
                    is NetworkState.Success -> {
                        withContext(Dispatchers.IO) { store.markSent(target) }
                        sent++
                    }
                    is NetworkState.Error -> {
                        sessionExpired = result.e is LoginException || result.e is CookiesExpiredException
                        unconfirmed = !sessionExpired
                        if (sessionExpired) withContext(Dispatchers.IO) { store.releaseExpired(target) }
                        remaining = queued.size - index - if (sessionExpired) 0 else 1
                        break
                    }
                }
            }
            ReplyOutcome(load(), sent, remaining, unconfirmed, sessionExpired)
        }
}

private suspend fun sendNeteaseReply(target: ReplyTarget, text: String): NetworkState<*> =
    when (target.source) {
        Source.COMMENT -> CommentUseCase(CommentRepositoryImpl.INSTANCE).replyComment(target.id, text)
        Source.FEEDBACK -> FeedbackUseCase(FeedbackRepositoryImpl.INSTANCE).sendReply(target.id, text)
    }
