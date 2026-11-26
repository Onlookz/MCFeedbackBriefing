package com.lemon.mcdevmanagermp.briefing

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.filechooser.FileSystemView

private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val CONTENT = "http://schemas.openxmlformats.org/package/2006/content-types"
private const val EXCEL = "application/vnd.openxmlformats-officedocument.spreadsheetml"
private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val WORD_DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val PICTURE = "http://schemas.openxmlformats.org/drawingml/2006/picture"
private val fileTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss").withZone(briefingZone)
private val documentTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(briefingZone)
private val excelStart = LocalDate.of(1899, 12, 30)

internal class BriefingWorkbook(
    private val directory: Path = FileSystemView.getFileSystemView().defaultDirectory.toPath().resolve("玩家反馈简报"),
    private val loadScreenshot: suspend (String) -> DocumentScreenshot? = ::loadDocumentScreenshot,
) {
    suspend fun export(
        accountId: Long,
        snapshot: BriefingSnapshot,
        format: BriefingExportFormat = BriefingExportFormat.XLSX,
    ): Path {
        require(snapshot.updatedAt > 0)
        Files.createDirectories(directory)
        val urls = if (format == BriefingExportFormat.DOCX) {
            snapshot.works.values.flatMap { work -> work.allTopics.flatMap(BriefingTopic::imageUrls) }.distinct()
        } else {
            emptyList()
        }
        if (urls.isEmpty()) return exportPrepared(accountId, snapshot, format, emptyMap())

        val staging = Files.createTempDirectory(directory, ".截图-")
        return try {
            val images = stageScreenshots(urls, staging)
            exportPrepared(accountId, snapshot, format, images)
        } finally {
            Files.newDirectoryStream(staging).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(staging)
        }
    }

    private suspend fun stageScreenshots(urls: List<String>, staging: Path): Map<String, EmbeddedScreenshot> =
        urls.withIndex().chunked(4).flatMap { batch ->
            coroutineScope {
                batch.map { (index, url) ->
                    async {
                        val image = loadScreenshot(url) ?: return@async null
                        val id = index + 1
                        val path = staging.resolve("image-$id.${image.format.extension}")
                        Files.write(path, image.bytes)
                        url to EmbeddedScreenshot(id, path, image.width, image.height, image.format, digest(image.bytes))
                    }
                }.awaitAll().filterNotNull()
            }
        }.toMap()

    private fun exportPrepared(
        accountId: Long,
        snapshot: BriefingSnapshot,
        format: BriefingExportFormat,
        images: Map<String, EmbeddedScreenshot>,
    ): Path {
        val parts = when (format) {
            BriefingExportFormat.XLSX -> spreadsheetParts(snapshot)
            BriefingExportFormat.DOCX -> documentParts(snapshot, images)
        }
        // 文档的整理时间按实际生成时间显示，用零点占位计算同内容的签名。
        val signatureParts = if (format == BriefingExportFormat.DOCX) {
            val dayStart = Instant.ofEpochMilli(snapshot.updatedAt).atZone(briefingZone).toLocalDate()
                .atStartOfDay(briefingZone).toInstant().toEpochMilli()
            documentParts(snapshot.copy(updatedAt = dayStart), images)
        } else parts
        val signature = digest(
            "$accountId:" + signatureParts.joinToString("|") { it.first + it.second } +
                images.values.joinToString("|") { it.hash },
        ).take(10)
        val time = Instant.ofEpochMilli(snapshot.updatedAt)
        val prefix = "玩家反馈简报 " + time.atZone(briefingZone).toLocalDate()
        val suffix = "-$signature." + format.extension
        Files.list(directory).use { paths ->
            val previous = paths.filter {
                val name = it.fileName.toString()
                name.startsWith(prefix) && name.endsWith(suffix) && Files.isRegularFile(it) && Files.size(it) > 0L
            }.findFirst()
            if (previous.isPresent) return previous.get()
        }

        val path = directory.resolve("玩家反馈简报 " + fileTime.format(time) + suffix)
        val temporary = Files.createTempFile(directory, "报告-", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                fun put(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                parts.forEach { (name, xml) -> put(name, xml) }
                images.values.forEach { image ->
                    zip.putNextEntry(ZipEntry("word/media/${image.path.fileName}"))
                    Files.copy(image.path, zip)
                    zip.closeEntry()
                }
            }
            try {
                Files.move(temporary, path)
            } catch (_: FileAlreadyExistsException) {
                // 另一个窗口已经写出了同一版本，保留它的文件。
                if (Files.size(path) == 0L) throw BriefingFailure("已有同名空表格，保存失败。")
            }
            return path
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun spreadsheetParts(snapshot: BriefingSnapshot): List<Pair<String, String>> {
        val sheets = buildSheets(snapshot)
        return buildList {
            add("[Content_Types].xml" to contentTypes(sheets.size))
            add("_rels/.rels" to "<Relationships xmlns=\"$RELS\"><Relationship Id=\"rId1\" Type=\"$OFFICE/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            add("xl/workbook.xml" to workbookXml(sheets))
            add("xl/_rels/workbook.xml.rels" to workbookRelations(sheets.size))
            add("xl/styles.xml" to STYLES)
            sheets.forEachIndexed { index, sheet -> add("xl/worksheets/sheet" + (index + 1) + ".xml" to sheetXml(sheet)) }
        }
    }

    private fun documentParts(
        snapshot: BriefingSnapshot,
        images: Map<String, EmbeddedScreenshot>,
    ): List<Pair<String, String>> {
        val document = buildString {
            append("<w:document xmlns:w=\"$WORD\"")
            if (images.isNotEmpty()) {
                append(" xmlns:r=\"$OFFICE\" xmlns:wp=\"$WORD_DRAWING\" xmlns:a=\"$DRAWING\" xmlns:pic=\"$PICTURE\"")
            }
            append("><w:body>")
            paragraph("玩家反馈简报", size = 38, bold = true, keepNext = true)
            val updated = documentTime.format(Instant.ofEpochMilli(snapshot.updatedAt))
            paragraph("整理时间 $updated", size = 20)
            paragraph("整理范围 ${snapshot.windowLabel}", size = 20)
            snapshot.coverageWarning?.let { paragraph(it, size = 20) }
            snapshot.sourceWarnings.values.forEach { paragraph(it, size = 20) }
            val works = snapshot.works.values.filter { it.allTopics.isNotEmpty() }.sortedBy(WorkBriefing::name)
            var drawingId = 0
            works.forEach { work ->
                paragraph(work.name, size = 29, bold = true, keepNext = true, before = 280)
                work.allTopics.forEach { topic ->
                    paragraph(topic.kind.label + " · " + topic.title, size = 23, bold = true, keepNext = true, before = 130)
                    val first = topic.firstFeedbackAt?.let { documentTime.format(Instant.ofEpochMilli(it)) }
                    val last = topic.latestFeedbackAt?.let { documentTime.format(Instant.ofEpochMilli(it)) }
                    val period = when {
                        first == null || last == null -> "未提供时间"
                        first == last -> first
                        else -> "$first 至 $last"
                    }
                    paragraph("反馈时间 $period · ${topic.records} 条 · 可识别玩家 ${topic.players} 位 · 截图 ${topic.imageUrls.size} 张", size = 20)
                    if (topic.trigger.isNotBlank()) paragraph("触发条件 " + topic.trigger, size = 21)
                    val attachments = topic.imageUrls.distinct()
                    val attached = attachments.mapNotNull(images::get)
                    attached.forEach { image -> screenshot(image, ++drawingId) }
                    val missing = attachments.size - attached.size
                    if (missing > 0) paragraph("有 $missing 张截图未能加载。", size = 20)
                }
            }
            append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1170\" w:right=\"1296\" w:bottom=\"1170\" w:left=\"1296\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>")
        }
        return buildList {
            val contentTypes = buildString {
                append("<Types xmlns=\"$CONTENT\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                images.values.map(EmbeddedScreenshot::format).distinct().forEach {
                    append("<Default Extension=\"${it.extension}\" ContentType=\"${it.contentType}\"/>")
                }
                append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>")
            }
            add("[Content_Types].xml" to contentTypes)
            add("_rels/.rels" to "<Relationships xmlns=\"$RELS\"><Relationship Id=\"rId1\" Type=\"$OFFICE/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
            add("word/document.xml" to document)
            if (images.isNotEmpty()) {
                val relationships = buildString {
                    append("<Relationships xmlns=\"$RELS\">")
                    images.values.forEach {
                        append("<Relationship Id=\"rIdImage${it.id}\" Type=\"$OFFICE/image\" Target=\"media/${it.path.fileName}\"/>")
                    }
                    append("</Relationships>")
                }
                add("word/_rels/document.xml.rels" to relationships)
            }
        }
    }

    private fun StringBuilder.screenshot(image: EmbeddedScreenshot, drawingId: Int) {
        val originalWidth = image.width * 9525.0
        val originalHeight = image.height * 9525.0
        val scale = minOf(1.0, 5_500_000 / originalWidth, 4_500_000 / originalHeight)
        val width = (originalWidth * scale).toLong().coerceAtLeast(1L)
        val height = (originalHeight * scale).toLong().coerceAtLeast(1L)
        append("<w:p><w:pPr><w:spacing w:before=\"40\" w:after=\"120\"/></w:pPr><w:r><w:drawing>")
        append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$width\" cy=\"$height\"/>")
        append("<wp:docPr id=\"$drawingId\" name=\"反馈截图 $drawingId\"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
        append("<a:graphic><a:graphicData uri=\"$PICTURE\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"$drawingId\" name=\"反馈截图 $drawingId\"/><pic:cNvPicPr/></pic:nvPicPr>")
        append("<pic:blipFill><a:blip r:embed=\"rIdImage${image.id}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
        append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$width\" cy=\"$height\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
        append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
    }

    private fun StringBuilder.paragraph(text: String, size: Int, bold: Boolean = false, keepNext: Boolean = false, before: Int = 0) {
        append("<w:p><w:pPr>")
        if (keepNext) append("<w:keepNext/>")
        append("<w:spacing w:before=\"$before\" w:after=\"80\"/></w:pPr><w:r><w:rPr>")
        append("<w:rFonts w:ascii=\"Microsoft YaHei\" w:eastAsia=\"Microsoft YaHei\"/>")
        if (bold) append("<w:b/>")
        append("<w:color w:val=\"252824\"/><w:sz w:val=\"$size\"/></w:rPr><w:t xml:space=\"preserve\">")
        append(xml(text))
        append("</w:t></w:r></w:p>")
    }

    private fun buildSheets(snapshot: BriefingSnapshot): List<Sheet> {
        val used = mutableSetOf("项目总览")
        val projects = snapshot.works.values.filter { it.allTopics.isNotEmpty() }.sortedBy(WorkBriefing::name)
        val details = projects.map { work ->
            Sheet(
                name = sheetName(work.name, used),
                headers = listOf("类型", "问题或建议", "触发条件", "反馈条数", "可识别玩家", "最早反馈时间", "最近反馈时间", "截图张数"),
                widths = listOf(15, 52, 46, 12, 14, 23, 23, 12),
                rows = work.allTopics.map { topic ->
                    listOf(
                        Cell(topic.kind.label), Cell(topic.title), Cell(topic.trigger),
                        count(topic.records), count(topic.players), time(topic.firstFeedbackAt),
                        time(topic.latestFeedbackAt), count(topic.imageUrls.size),
                    )
                },
            )
        }
        val overview = Sheet(
            name = "项目总览",
            headers = listOf("项目", "问题", "建议", "待补证据", "最早反馈时间", "最近反馈时间"),
            widths = listOf(43, 12, 12, 14, 23, 23),
            rows = projects.zip(details).map { (work, detail) ->
                val topics = work.allTopics
                listOf(
                    Cell(work.name, link = detail.name),
                    count(topics.count { it.kind == TopicKind.ISSUE }),
                    count(topics.count { it.kind == TopicKind.SUGGESTION }),
                    count(topics.count { it.kind == TopicKind.OBSERVE }),
                    time(topics.mapNotNull(BriefingTopic::firstFeedbackAt).minOrNull()),
                    time(topics.mapNotNull(BriefingTopic::latestFeedbackAt).maxOrNull()),
                )
            },
            notes = buildList {
                add("整理范围 ${snapshot.windowLabel}。评论 ${snapshot.commentCount} 条，玩家反馈 ${snapshot.feedbackCount} 条。")
                addAll(snapshot.sourceWarnings.values)
                snapshot.coverageWarning?.let { add(it) }
            },
        )
        return listOf(overview) + details
    }

    private fun sheetName(name: String, used: MutableSet<String>): String {
        val base = name.map { if (it in "\\/:?*[]" || it.isISOControl()) ' ' else it }
            .joinToString("").trim(' ', '\'').ifBlank { "未命名项目" }
        var candidate = limitSheetName(base, 31)
        var index = 2
        while (!used.add(candidate.lowercase())) {
            val suffix = " (" + index++ + ")"
            candidate = limitSheetName(base, 31 - suffix.length) + suffix
        }
        return candidate
    }

    private fun limitSheetName(value: String, limit: Int): String {
        val part = value.take(limit).trimEnd(' ', '\'')
        return if (part.lastOrNull()?.isHighSurrogate() == true) part.dropLast(1) else part
    }

    private fun count(value: Int) = Cell(number = value.toString())

    private fun time(millis: Long?): Cell {
        if (millis == null || millis <= 0L) return Cell()
        val local = Instant.ofEpochMilli(millis).atZone(briefingZone)
        val serial = ChronoUnit.DAYS.between(excelStart, local.toLocalDate()) +
            local.toLocalTime().toNanoOfDay().toDouble() / 86_400_000_000_000.0
        return Cell(number = serial.toString(), date = true)
    }

    private fun sheetXml(sheet: Sheet) = buildString {
        val width = sheet.headers.size
        val lastCol = ('A'.code + width - 1).toChar()
        append("<worksheet xmlns=\"$MAIN\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/></sheetView></sheetViews>")
        append("<sheetFormatPr defaultRowHeight=\"20\"/><cols>")
        sheet.widths.forEachIndexed { index, value ->
            val column = index + 1
            append("<col min=\"$column\" max=\"$column\" width=\"$value\" customWidth=\"1\"/>")
        }
        append("</cols><sheetData><row r=\"1\" ht=\"24\" customHeight=\"1\">")
        sheet.headers.forEachIndexed { index, name -> appendCell(index, 1, Cell(name), 1) }
        append("</row>")
        sheet.rows.forEachIndexed { index, cells ->
            val row = index + 2
            val lines = cells.mapIndexed { col, cell ->
                val length = cell.text.orEmpty().length
                val displayWidth = (sheet.widths[col] / 2).coerceAtLeast(1)
                (length + displayWidth - 1) / displayWidth
            }.maxOrNull()?.coerceIn(1, 5) ?: 1
            append("<row r=\"$row\" ht=\"" + (lines * 19 + 5) + "\" customHeight=\"1\">")
            cells.forEachIndexed { col, cell ->
                val style = when {
                    cell.link != null -> 4
                    cell.date -> 3
                    cell.number != null -> 0
                    else -> 2
                }
                appendCell(col, row, cell, style)
            }
            append("</row>")
        }
        val noteRow = sheet.rows.size + 3
        sheet.notes.forEachIndexed { index, note ->
            val row = noteRow + index
            append("<row r=\"$row\" ht=\"26\" customHeight=\"1\">")
            appendCell(0, row, Cell(note), 2)
            append("</row>")
        }
        append("</sheetData><autoFilter ref=\"A1:$lastCol" + (sheet.rows.size + 1) + "\"/>")
        if (sheet.notes.isNotEmpty()) {
            append("<mergeCells count=\"" + sheet.notes.size + "\">")
            sheet.notes.indices.forEach { index ->
                val row = noteRow + index
                append("<mergeCell ref=\"A$row:$lastCol$row\"/>")
            }
            append("</mergeCells>")
        }
        val links = sheet.rows.mapIndexedNotNull { index, cells -> cells.first().link?.let { index + 2 to it } }
        if (links.isNotEmpty()) {
            append("<hyperlinks>")
            links.forEach { (row, link) ->
                append("<hyperlink ref=\"A$row\" location=\"" + xml("'" + link.replace("'", "''") + "'!A1") + "\"/>")
            }
            append("</hyperlinks>")
        }
        append("</worksheet>")
    }

    private fun StringBuilder.appendCell(column: Int, row: Int, cell: Cell, style: Int) {
        val address = ('A'.code + column).toChar().toString() + row
        val number = cell.number
        if (number != null) append("<c r=\"$address\" s=\"$style\"><v>$number</v></c>")
        else if (cell.text.isNotEmpty()) append("<c r=\"$address\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" +
            xml(cell.text) + "</t></is></c>")
    }

    private fun contentTypes(count: Int) = buildString {
        append("<Types xmlns=\"$CONTENT\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"$EXCEL.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"$EXCEL.styles+xml\"/>")
        for (i in 1..count) append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"$EXCEL.worksheet+xml\"/>")
        append("</Types>")
    }

    private fun workbookXml(sheets: List<Sheet>) = buildString {
        append("<workbook xmlns=\"$MAIN\" xmlns:r=\"$OFFICE\"><bookViews><workbookView/></bookViews><sheets>")
        sheets.forEachIndexed { index, sheet ->
            val id = index + 1
            append("<sheet name=\"" + xml(sheet.name) + "\" sheetId=\"$id\" r:id=\"rId$id\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRelations(count: Int) = buildString {
        append("<Relationships xmlns=\"$RELS\">")
        for (i in 1..count) append("<Relationship Id=\"rId$i\" Type=\"$OFFICE/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        append("<Relationship Id=\"rId" + (count + 1) + "\" Type=\"$OFFICE/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun xml(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\t', '\r', '\n' -> append(ch)
                else -> if (ch >= ' ' && ch != '\uFFFE' && ch != '\uFFFF') append(ch)
            }
        }
    }

    private data class Sheet(
        val name: String,
        val headers: List<String>,
        val widths: List<Int>,
        val rows: List<List<Cell>>,
        val notes: List<String> = emptyList(),
    )

    private data class Cell(
        val text: String = "",
        val number: String? = null,
        val date: Boolean = false,
        val link: String? = null,
    )

    private data class EmbeddedScreenshot(
        val id: Int,
        val path: Path,
        val width: Int,
        val height: Int,
        val format: DocumentScreenshotFormat,
        val hash: String,
    )

    private companion object {
        val STYLES = """
            <styleSheet xmlns="$MAIN">
            <numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm"/></numFmts>
            <fonts count="3">
              <font><sz val="11"/><color rgb="FF252824"/><name val="Microsoft YaHei"/></font>
              <font><b/><sz val="11"/><color rgb="FF252824"/><name val="Microsoft YaHei"/></font>
              <font><u/><sz val="11"/><color rgb="FF324B55"/><name val="Microsoft YaHei"/></font>
            </fonts>
            <fills count="3">
              <fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
              <fill><patternFill patternType="solid"><fgColor rgb="FFCFD2CD"/><bgColor indexed="64"/></patternFill></fill>
            </fills>
            <borders count="2">
              <border><left/><right/><top/><bottom/><diagonal/></border>
              <border><left/><right/><top/><bottom style="thin"><color rgb="FFA9AEA6"/></bottom><diagonal/></border>
            </borders>
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
            <cellXfs count="5">
              <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
              <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>
              <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
              <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
              <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
            </cellXfs>
            <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
            </styleSheet>
        """.trimIndent()
    }
}
