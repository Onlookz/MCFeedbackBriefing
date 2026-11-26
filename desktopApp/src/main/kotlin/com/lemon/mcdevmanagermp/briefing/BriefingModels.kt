package com.lemon.mcdevmanagermp.briefing

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.prefs.Preferences

internal val briefingJson = Json { ignoreUnknownKeys = true }
internal val briefingZone: ZoneId = ZoneId.of("Asia/Shanghai")

internal class BriefingFailure(message: String) : Exception(message)
internal class BriefingSessionExpired : Exception()

internal class AiConnection(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val rememberApiKey: Boolean = false,
    val restoreWarning: String? = null,
) {
    fun endpoint(): URI {
        val base = try {
            URI(baseUrl.trim())
        } catch (_: URISyntaxException) {
            throw BriefingFailure("AI 地址格式有误。")
        }
        val host = base.host?.lowercase()
        val local = host in setOf("localhost", "127.0.0.1", "[::1]")
        if (host == null || base.userInfo != null || base.query != null || base.fragment != null ||
            (base.scheme != "https" && !(base.scheme == "http" && local))
        ) {
            throw BriefingFailure("请输入 HTTPS 地址，或本机模型的 HTTP 地址。")
        }
        if (model.isBlank()) throw BriefingFailure("请输入模型名称。")
        if (apiKey.isBlank() && restoreWarning != null) throw BriefingFailure(restoreWarning)
        if (host == "api.deepseek.com" && apiKey.isBlank()) {
            throw BriefingFailure("请在「AI 连接」填入 DeepSeek API Key，再开始整理。")
        }
        if (apiKey.any { it.code < 32 || it.code > 126 }) {
            throw BriefingFailure("API Key 格式有误。")
        }
        return URI(base.toString().trimEnd('/') + "/chat/completions")
    }
}

@Serializable
internal enum class Source(val label: String) {
    COMMENT("评论"), FEEDBACK("玩家反馈")
}

internal data class PlayerEntry(
    val source: Source,
    val id: String,
    val workId: String,
    val workName: String,
    val authorId: String,
    val time: Long,
    val text: String,
    val imageUrls: List<String> = emptyList(),
    val replyAllowed: Boolean = true,
) {
    val key: String = source.name + ":" +
        id.takeUnless { it.isBlank() || it == "0" }.orEmpty().ifEmpty {
            digest("$workId|$authorId|$time|$text")
        }

    val publishedAt: Long
        get() = if (time in 1..999_999_999_999L) time * 1000 else time

    val originalReference: OriginalReference
        get() = OriginalReference(source, key)

    val replyTarget: ReplyTarget?
        get() = id.takeIf { replyAllowed && it.isNotBlank() && it != "0" }?.let {
            ReplyTarget(source, it, publishedAt)
        }

    val workKey: String
        get() = if (workId.isNotBlank()) "id:$workId" else "name:$workName"
}

@Serializable
internal enum class TopicKind(val label: String) {
    ISSUE("问题"), SUGGESTION("建议"), OBSERVE("待补证据")
}

internal enum class BriefingTopicOrder(val label: String) {
    CATEGORY("按分类"),
    NEWEST("最新优先"),
    OLDEST("最早优先"),
    MOST_REPORTED("反馈最多");

    fun apply(topics: List<BriefingTopic>): List<BriefingTopic> = when (this) {
        CATEGORY -> topics
        NEWEST -> topics.sortedByDescending { it.latestFeedbackAt ?: Long.MIN_VALUE }
        OLDEST -> topics.sortedBy { it.firstFeedbackAt ?: Long.MAX_VALUE }
        MOST_REPORTED -> topics.sortedByDescending(BriefingTopic::records)
    }
}

@Serializable
internal data class OriginalReference(val source: Source, val key: String)

@Serializable
internal data class BriefingTopic(
    val kind: TopicKind,
    val title: String,
    val trigger: String,
    val records: Int,
    val players: Int,
    val unknownPlayers: Boolean,
    val imageUrls: List<String> = emptyList(),
    val sourceIndexes: List<Int> = emptyList(),
    val replyTargets: List<ReplyTarget> = emptyList(),
    val firstFeedbackAt: Long? = null,
    val latestFeedbackAt: Long? = null,
    val originalReferences: List<OriginalReference> = emptyList(),
) {
    fun originalReferencesOrLegacy(): List<OriginalReference> {
        if (originalReferences.isNotEmpty()) return originalReferences
        val legacy = replyTargets.distinctBy(ReplyTarget::key)
        return if (legacy.size == records) legacy.map { OriginalReference(it.source, it.key) } else emptyList()
    }
}

@Serializable
internal data class WorkBriefing(
    val name: String,
    val inputHash: String,
    val topics: List<BriefingTopic>,
    val imageOnlyFeedback: BriefingTopic? = null,
) {
    val allTopics: List<BriefingTopic> get() = topics + listOfNotNull(imageOnlyFeedback)
}

@Serializable
internal data class BriefingSnapshot(
    val updatedAt: Long = 0L,
    val works: Map<String, WorkBriefing> = emptyMap(),
    val commentCount: Int = 0,
    val feedbackCount: Int = 0,
    val capped: Boolean = false,
    val sourceWarnings: Map<Source, String> = emptyMap(),
    val window: BriefingWindow? = null,
) {
    val windowLabel: String get() = window?.label ?: "近 7 天"
    val coverageWarning: String? get() = if (capped) "这份旧简报达到当时单个来源 200 条的读取上限。" else null
}

internal fun feedbackImageUrl(raw: String): String? {
    val trimmed = raw.trim().substringBefore('#')
    val url = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return null
    }
    val host = uri.host?.lowercase() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
        '.' !in host || host.all { it.isDigit() || it == '.' } ||
        host.endsWith(".local") || host.endsWith(".localhost")
    ) return null
    return url
}

internal fun digest(text: String): String = digest(text.toByteArray(Charsets.UTF_8))

internal fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal val briefingDataDirectory: Path = Path.of(
    System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
    "MCFeedbackBriefing",
)

internal fun writeBriefingFile(file: Path, content: String) {
    Files.createDirectories(file.parent)
    val tempFile = Files.createTempFile(file.parent, "briefing-", ".tmp")
    try {
        Files.writeString(tempFile, content)
        Files.move(tempFile, file, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(tempFile)
    }
}

internal class BriefingStore(
    private val directory: Path = briefingDataDirectory,
    private val prefs: Preferences = Preferences.userRoot().node("mc-feedback-briefing/ai"),
) {
    fun loadConnection(): AiConnection {
        val environmentKey = System.getenv("MC_BRIEFING_API_KEY").orEmpty()
        val encoded = prefs.get("connection", null)
        val saved = try {
            encoded?.let { briefingJson.decodeFromString<SavedAiConnection>(it) }
        } catch (_: SerializationException) {
            return loadPreviousConnection(environmentKey, "无法读取上次的 AI 连接设置，请重新保存。")
        }
        if (saved == null) return loadPreviousConnection(environmentKey)
        val protected = saved.protectedApiKey
        if (protected == null) return AiConnection(saved.baseUrl, saved.model, environmentKey)

        val key = try {
            if (Platform.isWindows()) {
                val ciphertext = Base64.getDecoder().decode(protected)
                Crypt32Util.cryptUnprotectData(ciphertext, CRYPTPROTECT_UI_FORBIDDEN).toString(Charsets.UTF_8)
            } else {
                null
            }
        } catch (_: Win32Exception) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return AiConnection(
            baseUrl = saved.baseUrl,
            model = saved.model,
            apiKey = key ?: environmentKey,
            rememberApiKey = key != null,
            restoreWarning = if (key == null) "无法读取已保存的 API Key，请重新填写后保存。" else null,
        )
    }

    fun saveConnection(connection: AiConnection) {
        val protected = if (connection.rememberApiKey && connection.apiKey.isNotBlank()) {
            val ciphertext = Crypt32Util.cryptProtectData(
                connection.apiKey.toByteArray(Charsets.UTF_8),
                CRYPTPROTECT_UI_FORBIDDEN,
            )
            Base64.getEncoder().encodeToString(ciphertext)
        } else {
            null
        }
        val saved = SavedAiConnection(connection.baseUrl, connection.model, protected)
        prefs.put("connection", briefingJson.encodeToString(saved))
        prefs.flush()
    }

    private fun loadPreviousConnection(apiKey: String, warning: String? = null): AiConnection = AiConnection(
        prefs.get("baseUrl", System.getenv("MC_BRIEFING_BASE_URL").orEmpty()),
        prefs.get("model", System.getenv("MC_BRIEFING_MODEL").orEmpty()),
        apiKey,
        restoreWarning = warning,
    )

    fun loadDateFilter(accountId: Long): BriefingDateFilter {
        val dates = prefs.get("dates-$accountId", "").split('/')
        if (dates.size != 2) return BriefingDateFilter.LastDay
        return try {
            val start = LocalDate.parse(dates[0])
            val end = LocalDate.parse(dates[1])
            if (start <= end) BriefingDateFilter.Custom(start, end) else BriefingDateFilter.LastDay
        } catch (_: DateTimeParseException) {
            BriefingDateFilter.LastDay
        }
    }

    fun saveDateFilter(accountId: Long, filter: BriefingDateFilter) {
        val key = "dates-$accountId"
        when (filter) {
            BriefingDateFilter.LastDay -> prefs.remove(key)
            is BriefingDateFilter.Custom -> prefs.put(key, "${filter.start}/${filter.end}")
        }
        prefs.flush()
    }

    fun loadTopicOrder(accountId: Long): BriefingTopicOrder {
        val saved = prefs.get("topic-order-$accountId", "")
        return BriefingTopicOrder.entries.firstOrNull { it.name == saved } ?: BriefingTopicOrder.CATEGORY
    }

    fun saveTopicOrder(accountId: Long, order: BriefingTopicOrder) {
        prefs.put("topic-order-$accountId", order.name)
    }

    fun loadProjectFilter(accountId: Long): ProjectFilter {
        val saved = prefs.get("project-filter-$accountId", "")
        return ProjectFilter.entries.firstOrNull { it.name == saved } ?: ProjectFilter.PUBLISHED
    }

    fun saveProjectFilter(accountId: Long, filter: ProjectFilter) {
        prefs.put("project-filter-$accountId", filter.name)
    }

    fun load(accountId: Long): BriefingSnapshot {
        val file = snapshotFile(accountId)
        if (Files.notExists(file)) return BriefingSnapshot()
        return briefingJson.decodeFromString(Files.readString(file))
    }

    fun save(accountId: Long, snapshot: BriefingSnapshot) {
        writeBriefingFile(snapshotFile(accountId), briefingJson.encodeToString(snapshot))
    }

    fun loadProjects(accountId: Long): Map<String, BriefingProject> {
        val file = projectsFile(accountId)
        if (Files.notExists(file)) return emptyMap()
        return briefingJson.parseToJsonElement(Files.readString(file)).jsonObject.mapValues { (_, entry) ->
            if (entry is JsonPrimitive && entry.isString) BriefingProject(entry.content)
            else briefingJson.decodeFromJsonElement<BriefingProject>(entry)
        }
    }

    fun saveProjects(accountId: Long, projects: Map<String, BriefingProject>) {
        writeBriefingFile(projectsFile(accountId), briefingJson.encodeToString(projects))
    }

    private fun snapshotFile(accountId: Long): Path = directory.resolve("account-$accountId.json")
    private fun projectsFile(accountId: Long): Path = directory.resolve("projects-$accountId.json")

    @Serializable
    private data class SavedAiConnection(
        val baseUrl: String,
        val model: String,
        val protectedApiKey: String? = null,
    )
}
