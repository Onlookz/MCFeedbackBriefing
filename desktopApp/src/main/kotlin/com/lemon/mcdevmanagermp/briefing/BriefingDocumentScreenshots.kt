package com.lemon.mcdevmanagermp.briefing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

private const val MAX_SCREENSHOT_BYTES = 16 * 1024 * 1024
private const val MAX_SCREENSHOT_PIXELS = 24_000_000
private val screenshotHttp by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}

internal enum class DocumentScreenshotFormat(val extension: String, val contentType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
}

internal data class DocumentScreenshot(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val format: DocumentScreenshotFormat,
)

internal suspend fun loadDocumentScreenshot(url: String): DocumentScreenshot? {
    val uri = feedbackImageUrl(url)?.let(URI::create) ?: return null
    return try {
        val bytes = withTimeoutOrNull(15_000) {
            runInterruptible(Dispatchers.IO) {
                val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET().build()
                val response = screenshotHttp.send(request, HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { body ->
                    if (response.statusCode() == 200) body.readNBytes(MAX_SCREENSHOT_BYTES + 1) else null
                }
            }
        }
        bytes?.let(::decodeDocumentScreenshot)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun decodeDocumentScreenshot(bytes: ByteArray): DocumentScreenshot? {
    if (bytes.size !in 1..MAX_SCREENSHOT_BYTES) return null
    MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.setInput(input, true, true)
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SCREENSHOT_PIXELS) return null
            return when (reader.formatName.lowercase()) {
                "jpeg", "jpg" -> DocumentScreenshot(bytes, width, height, DocumentScreenshotFormat.JPEG)
                "png" -> DocumentScreenshot(bytes, width, height, DocumentScreenshotFormat.PNG)
                else -> {
                    val image = reader.read(0)
                    val output = ByteArrayOutputStream()
                    if (!ImageIO.write(image, "png", output) || output.size() > MAX_SCREENSHOT_BYTES) return null
                    DocumentScreenshot(output.toByteArray(), width, height, DocumentScreenshotFormat.PNG)
                }
            }
        } finally {
            reader.dispose()
        }
    }
}
