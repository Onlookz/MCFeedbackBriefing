package com.lemon.mcdevmanagermp.briefing

import com.lemon.mcdevmanagermp.data.consts.enums.WorkItemStatusEnum
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BriefingProjectsTest {
    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun showsOnlyPublishedProjectsAndKeepsPublishedProjectsWithoutCards() {
        val issue = BriefingTopic(TopicKind.ISSUE, "测试问题", "", 1, 1, false)
        val works = mapOf(
            "id:a" to WorkBriefing("旧标题", "hash", listOf(issue)),
            "id:c" to WorkBriefing("审核中的项目", "hash-c", listOf(issue)),
            "id:d" to WorkBriefing("目录外的旧项目", "hash-d", listOf(issue)),
            "id:e" to WorkBriefing("弱下架项目", "hash-e", listOf(issue)),
        )
        val directory = mapOf(
            "id:a" to BriefingProject("现标题", WorkItemStatusEnum.ONLINE),
            "id:b" to BriefingProject("暂无反馈的项目", WorkItemStatusEnum.ONLINE),
            "id:c" to BriefingProject("审核中的项目", WorkItemStatusEnum.REVIEWING),
            "id:e" to BriefingProject("弱下架项目", WorkItemStatusEnum.ONLINE, weakOffline = true),
            "id:f" to BriefingProject("待上架项目", WorkItemStatusEnum.ACCEPT),
        )

        val rows = projectBriefings(directory, works, ProjectFilter.PUBLISHED)

        assertEquals(listOf("id:a", "id:b"), rows.map { it.key })
        val result = rows.associateBy { it.key }
        assertEquals("现标题", result.getValue("id:a").name)
        assertEquals(listOf(issue), result.getValue("id:a").allTopics)
        assertTrue(result.getValue("id:b").allTopics.isEmpty())

        val all = projectBriefings(directory, works, ProjectFilter.ALL).associateBy { it.key }
        assertEquals(setOf("id:a", "id:b", "id:c", "id:d", "id:e", "id:f"), all.keys)
        assertTrue(all.getValue("id:b").allTopics.isEmpty())
        assertTrue(all.getValue("id:f").allTopics.isEmpty())
        assertEquals("审核中", all.getValue("id:c").project.statusLabel)
        assertEquals("弱下架", all.getValue("id:e").project.statusLabel)
    }

    @Test
    fun storesASeparateProjectDirectoryForEachAccount() {
        val path = temp.root.toPath()
        val directory = mapOf(
            "id:42" to BriefingProject("测试项目", WorkItemStatusEnum.ONLINE),
            "id:43" to BriefingProject("弱下架测试", WorkItemStatusEnum.ONLINE, weakOffline = true),
        )
        BriefingStore(path).saveProjects(1, directory)

        val reopened = BriefingStore(path)
        assertEquals(directory, reopened.loadProjects(1))
        assertTrue(reopened.loadProjects(2).isEmpty())
    }

    @Test
    fun keepsNamesFromThePreviousCacheWithoutTreatingThemAsPublished() {
        val path = temp.root.toPath()
        writeBriefingFile(path.resolve("projects-1.json"), """{"id:42":"旧目录项目"}""")
        val old = BriefingStore(path).loadProjects(1).getValue("id:42")
        assertEquals("旧目录项目", old.name)
        assertFalse(old.isPublished)
        val directory = mapOf("id:42" to old)
        assertTrue(projectBriefings(directory, emptyMap(), ProjectFilter.PUBLISHED).isEmpty())
        assertEquals("旧目录项目", projectBriefings(directory, emptyMap(), ProjectFilter.ALL).single().name)
    }

    @Test
    fun remembersTheProjectFilterPerAccount() {
        val prefs = Preferences.userRoot().node("mcbriefing-project-test-" + UUID.randomUUID())
        try {
            val store = BriefingStore(temp.root.toPath(), prefs)
            assertEquals(ProjectFilter.PUBLISHED, store.loadProjectFilter(1))
            store.saveProjectFilter(1, ProjectFilter.ALL)

            val reopened = BriefingStore(temp.root.toPath(), prefs)
            assertEquals(ProjectFilter.ALL, reopened.loadProjectFilter(1))
            assertEquals(ProjectFilter.PUBLISHED, reopened.loadProjectFilter(2))
        } finally {
            prefs.removeNode()
        }
    }
}
