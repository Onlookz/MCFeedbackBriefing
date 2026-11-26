package com.lemon.mcdevmanagermp.briefing

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BriefingWorkbookTest {
    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun writesProjectSheetsWithWorkbookDatesAndLiteralText() = runBlocking {
        val saved = Instant.parse("2026-11-26T12:00:00Z").toEpochMilli()
        val report = BriefingSnapshot(
            updatedAt = saved,
            window = BriefingDateFilter.LastDay.windowAt(Instant.ofEpochMilli(saved)),
            works = linkedMapOf(
                "1" to WorkBriefing("项目/B", "", listOf(
                    BriefingTopic(TopicKind.ISSUE, "=1+1 & <问题>", "多人联机", 2, 2, false,
                        firstFeedbackAt = saved, latestFeedbackAt = saved + 3_600_000),
                )),
                "2" to WorkBriefing("项目:B", "", listOf(
                    BriefingTopic(TopicKind.SUGGESTION, "建议", "", 1, 1, false),
                )),
            ),
        )
        val book = BriefingWorkbook(temp.root.toPath()).export(1, report)
        assertTrue(book.fileName.toString().endsWith(".xlsx"))
        ZipFile(book.toFile()).use { zip ->
            val workbook = xml(zip, "xl/workbook.xml")
            val sheets = workbook.getElementsByTagName("sheet")
            assertEquals(3, sheets.length)
            assertEquals("项目总览", sheets.item(0).attributes.getNamedItem("name").nodeValue)
            assertEquals("项目 B", sheets.item(1).attributes.getNamedItem("name").nodeValue)
            assertEquals("项目 B (2)", sheets.item(2).attributes.getNamedItem("name").nodeValue)
            val overview = xml(zip, "xl/worksheets/sheet1.xml")
            assertTrue(overview.documentElement.textContent.contains("2026-11-25 20:00 至 2026-11-26 20:00"))
            assertEquals("'项目 B'!A1", overview.getElementsByTagName("hyperlink").item(0)
                .attributes.getNamedItem("location").nodeValue)

            val sheet = xml(zip, "xl/worksheets/sheet2.xml")
            val cells = sheet.getElementsByTagName("c")
            val byName = (0 until cells.length).map(cells::item)
                .associateBy { it.attributes.getNamedItem("r").nodeValue }
            assertEquals("inlineStr", byName.getValue("B2").attributes.getNamedItem("t").nodeValue)
            assertEquals("=1+1 & <问题>", byName.getValue("B2").textContent)
            assertEquals("3", byName.getValue("F2").attributes.getNamedItem("s").nodeValue)
            assertEquals(1.0 / 24, byName.getValue("G2").textContent.toDouble() -
                byName.getValue("F2").textContent.toDouble(), 0.000001)
        }
    }

    @Test
    fun reusesUnchangedReportsAndKeepsEarlierFiles() = runBlocking {
        val saved = Instant.parse("2026-11-26T10:00:00Z").toEpochMilli()
        val report = BriefingSnapshot(updatedAt = saved)
        val exporter = BriefingWorkbook(temp.root.toPath())
        val first = exporter.export(1, report)
        assertEquals(first, exporter.export(1, report.copy(updatedAt = saved + 3600_000)))
        val changed = exporter.export(1, report.copy(commentCount = 5, updatedAt = saved + 3600_000))
        assertTrue(first != changed)
        assertTrue(first.toFile().isFile)
        assertTrue(changed.toFile().isFile)
    }

    @Test
    fun exportsOnlySelectedTopicsAsAnEditableWordDocument() = runBlocking {
        val date = Instant.parse("2026-11-26T12:00:00Z").toEpochMilli()
        val selected = BriefingTopic(TopicKind.ISSUE, "选中的问题", "联机", 2, 2, false,
            firstFeedbackAt = date, latestFeedbackAt = date + 3_600_000)
        val unselected = selected.copy(title = "未选中的问题")
        val report = BriefingSnapshot(
            updatedAt = date,
            window = BriefingDateFilter.Custom(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 26))
                .windowAt(Instant.ofEpochMilli(date)),
            works = mapOf(
                "one" to WorkBriefing("第一个项目", "", listOf(selected, unselected)),
                "two" to WorkBriefing("第二个项目", "", listOf(unselected)),
            ),
        )
        val plan = BriefingExportPlan(
            format = BriefingExportFormat.DOCX,
            projects = mapOf("one" to ExportProject(false, setOf(selected.exportKey))),
            automatic = true,
        )
        val scoped = report.selectedBy(plan)
        assertEquals(listOf("one"), scoped.works.keys.toList())
        assertEquals(listOf(selected), scoped.works.getValue("one").topics)
        val exporter = BriefingWorkbook(temp.root.toPath())
        val path = exporter.export(1, scoped, plan.format)
        assertTrue(path.fileName.toString().endsWith(".docx"))
        assertEquals(path, exporter.export(1, scoped.copy(updatedAt = date + 60_000), plan.format))
        ZipFile(path.toFile()).use { zip ->
            val text = xml(zip, "word/document.xml").documentElement.textContent
            assertTrue(text.contains("选中的问题"))
            assertTrue(text.contains("整理范围 2026-11-01 至 2026-11-26"))
            assertTrue(text.contains("2026-11-26 20:00 至 2026-11-26 21:00"))
            assertTrue(!text.contains("未选中的问题") && !text.contains("第二个项目"))
        }
    }

    @Test
    fun embedsScreenshotsUnderSelectedTopicsAndRetriesUnavailableAttachments(): Unit = runBlocking {
        val firstUrl = "https://screenshots.example.test/one"
        val secondUrl = "https://screenshots.example.test/two"
        val ignoredUrl = "https://screenshots.example.test/not-selected"
        val first = BriefingTopic(TopicKind.ISSUE, "第一条问题", "", 1, 1, false, imageUrls = listOf(firstUrl, secondUrl))
        val second = first.copy(title = "第二条问题", imageUrls = listOf(firstUrl))
        val ignored = first.copy(title = "未选条目", imageUrls = listOf(ignoredUrl))
        val report = BriefingSnapshot(
            updatedAt = Instant.parse("2026-11-26T12:00:00Z").toEpochMilli(),
            works = mapOf("work" to WorkBriefing("测试项目", "", listOf(first, second, ignored))),
        )
        val plan = BriefingExportPlan(
            format = BriefingExportFormat.DOCX,
            projects = mapOf("work" to ExportProject(false, setOf(first.exportKey, second.exportKey))),
        )
        val png = fixtureImage("png")
        val jpeg = fixtureImage("jpg")
        val loaded = mutableListOf<String>()
        var secondAvailable = false
        val exporter = BriefingWorkbook(temp.root.toPath()) { url ->
            loaded += url
            when (url) {
                firstUrl -> decodeDocumentScreenshot(png)
                secondUrl -> if (secondAvailable) decodeDocumentScreenshot(jpeg) else null
                else -> error("不得下载未选中的截图")
            }
        }

        val scoped = report.selectedBy(plan)
        val partial = exporter.export(1, scoped, plan.format)
        ZipFile(partial.toFile()).use { zip ->
            val document = xml(zip, "word/document.xml")
            assertEquals(2, document.getElementsByTagName("wp:inline").length)
            assertTrue(document.documentElement.textContent.contains("有 1 张截图未能加载。"))
        }

        secondAvailable = true
        val complete = exporter.export(1, scoped, plan.format)
        assertNotEquals(partial, complete)
        assertEquals(listOf(firstUrl, secondUrl, firstUrl, secondUrl), loaded)
        ZipFile(complete.toFile()).use { zip ->
            val document = xml(zip, "word/document.xml")
            assertEquals(3, document.getElementsByTagName("wp:inline").length)
            assertTrue(!document.documentElement.textContent.contains("截图未能加载"))
            assertTrue(!document.documentElement.textContent.contains("未选条目"))
            val paragraphs = document.getElementsByTagName("w:p")
            val nodes = (0 until paragraphs.length).map(paragraphs::item)
            val firstDrawing = nodes.indexOfFirst { it.firstChild?.nextSibling?.firstChild?.nodeName == "w:drawing" }
            val firstTitle = nodes.indexOfFirst { it.textContent.contains("第一条问题") }
            val secondTitle = nodes.indexOfFirst { it.textContent.contains("第二条问题") }
            assertTrue(firstDrawing in (firstTitle + 1) until secondTitle)
            val relationships = xml(zip, "word/_rels/document.xml.rels").getElementsByTagName("Relationship")
            assertEquals(2, relationships.length)
            val target = relationships.item(0).attributes.getNamedItem("Target").nodeValue
            val image = assertNotNull(zip.getEntry("word/$target"))
            zip.getInputStream(image).use { assertTrue(it.readBytes().contentEquals(png)) }
            assertTrue(zip.getEntry("word/media/image-2.jpg") != null)
        }
    }

    private fun fixtureImage(format: String): ByteArray = ByteArrayOutputStream().use { buffer ->
        check(ImageIO.write(BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB), format, buffer))
        buffer.toByteArray()
    }

    private fun xml(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).use {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
    }
}
