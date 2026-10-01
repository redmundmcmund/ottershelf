package io.github.ottershelf.devicetest

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.feature.pdf.PdfEngine
import io.github.ottershelf.feature.pdf.PdfPasswordException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * The PDF reader's engine on the phone, with whichever renderer it has ([PdfEngine]): Android 15's
 * PdfRenderer, PdfRendererPreV on Android 12 to 14 with the PDF module's S extension 13, or the
 * original renderer, which only draws pages. A two-page PDF written here (Helvetica text, a web
 * link and a link to page 2) opens, measures and draws; with the newer renderers its text is
 * searched and its web link read, and a copy protected by a password (RC4, the standard security
 * handler's revision 2) opens only with that password. With the original renderer search finds
 * nothing and the protected copy is refused as one it can't unlock. The first page is saved to
 * `device-tests/pdf/` and the renderer to `report.txt`.
 */
@RunWith(AndroidJUnit4::class)
class PdfDeviceTest {

    private val hasText = Build.VERSION.SDK_INT >= 35 || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

    @Test
    fun opensDrawsSearchesAndReadsLinks() = runBlocking {
        Device.report("pdf: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), S extension ${SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S)}, text features expected: $hasText")
        val file = File(Device.fixtureDir("pdf"), "plain.pdf").apply { writeBytes(TestPdf.write(password = null)) }
        val engine = PdfEngine.open(file, password = null)
        try {
            assertEquals(2, engine.pageCount)
            assertEquals(612f to 792f, engine.pageSize(0))
            assertEquals(hasText, engine.searchable)
            val page = checkNotNull(engine.render(0, 612, 792))
            File(Device.outDir, "pdf/page1.png").apply { parentFile?.mkdirs() }.outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("the page's text was drawn", darkPixels(page) > 500)
            val hits = engine.search(0, "otter")
            val links = engine.links(0).orEmpty()
            Device.report("pdf: ${hits.size} hits on page 1 (${hits.joinToString { it.snippet }}), links ${links.map { it.uri ?: "page ${it.page}" }}")
            if (hasText) {
                // "Ottershelf" and "otter": pdfium's search ignores case.
                assertEquals(2, hits.size)
                assertTrue(hits.all { it.rects.isNotEmpty() })
                assertEquals(1, engine.search(1, "otter").size)
                assertEquals(listOf("https://example.org/"), links.mapNotNull { it.uri })
                // Links inside the document (to page 2 here) aren't asserted: Android 17's getGotoLinks
                // returned none for this one, written as /Dest, /Dest with /Fit, or a GoTo action.
            } else {
                assertTrue(hits.isEmpty())
                assertTrue(links.isEmpty())
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun protectedPdfOpensOnlyWithItsPassword() = runBlocking {
        val file = File(Device.fixtureDir("pdf-locked"), "locked.pdf").apply { writeBytes(TestPdf.write(password = "otter")) }
        val missing = refusal(file, null)
        Device.report("pdf: protected, no password: ${missing?.let { "refused, canUnlock=${it.canUnlock}" } ?: "opened"}")
        if (!hasText) {
            assertEquals(false, missing?.canUnlock)
            assertEquals(false, refusal(file, "otter")?.canUnlock)
            return@runBlocking
        }
        assertEquals(true, missing?.canUnlock)
        assertEquals(true, refusal(file, "wrong")?.canUnlock)
        val engine = PdfEngine.open(file, "otter")
        try {
            assertEquals(2, engine.pageCount)
            assertTrue("the decrypted page was drawn", darkPixels(checkNotNull(engine.render(0, 612, 792))) > 500)
            assertEquals(2, engine.search(0, "otter").size)
            assertEquals(listOf("https://example.org/"), engine.links(0).orEmpty().mapNotNull { it.uri })
        } finally {
            engine.close()
        }
    }

    /** The [PdfPasswordException] opening [file] with [password] throws, or null when it opens. */
    private suspend fun refusal(file: File, password: String?): PdfPasswordException? = try {
        PdfEngine.open(file, password).close()
        if (password == null || password == "wrong") fail("opened without the right password")
        null
    } catch (e: PdfPasswordException) {
        e
    }

    private fun darkPixels(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { Color.red(it) < 128 && Color.green(it) < 128 && Color.blue(it) < 128 }
    }
}

/**
 * A two-page PDF written by hand: Helvetica text with "otter" in it twice on page 1 and once on
 * page 2, and on page 1 a web link and a link to page 2. With a [password] it is encrypted with
 * the standard security handler, revision 2 (40-bit RC4; the owner password the same).
 */
private object TestPdf {
    private val PAD = intArrayOf(
        0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
    ).map { it.toByte() }.toByteArray()
    private val ID = "0123456789abcdef".toByteArray()
    private const val PERMISSIONS = -4

    fun write(password: String?): ByteArray {
        val key = password?.let(::fileKey)
        fun stream(num: Int, text: String): String {
            val data = text.toByteArray(Charsets.ISO_8859_1).let { if (key != null) rc4(objectKey(key, num), it) else it }
            return "<< /Length ${data.size} >>\nstream\n" + data.toString(Charsets.ISO_8859_1) + "\nendstream"
        }
        fun string(num: Int, text: String): String =
            if (key == null) "($text)" else "<" + hex(rc4(objectKey(key, num), text.toByteArray(Charsets.ISO_8859_1))) + ">"
        val objects = mutableListOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 7 0 R >> >> /Annots [8 0 R 9 0 R] >>",
            stream(4, "BT /F1 24 Tf 72 700 Td (Ottershelf finds the otter here) Tj ET BT /F1 18 Tf 72 610 Td (Web link) Tj ET BT /F1 18 Tf 72 510 Td (Go to page two) Tj ET"),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R /Resources << /Font << /F1 7 0 R >> >> >>",
            stream(6, "BT /F1 24 Tf 72 700 Td (Page two has one otter) Tj ET"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /Annot /Subtype /Link /Rect [72 600 300 630] /Border [0 0 0] /A << /S /URI /URI ${string(8, "https://example.org/")} >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [72 500 300 530] /Border [0 0 0] /Dest [5 0 R /XYZ 0 792 0] >>",
        )
        if (password != null) {
            objects += "<< /Filter /Standard /V 1 /R 2 /O <${hex(ownerValue(password))}> /U <${hex(rc4(checkNotNull(key), PAD))}> /P $PERMISSIONS >>"
        }
        val out = ByteArrayOutputStream()
        fun put(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        put("%PDF-1.4\n%âãÏÓ\n")
        val offsets = objects.mapIndexed { i, body ->
            val at = out.size()
            put("${i + 1} 0 obj\n$body\nendobj\n")
            at
        }
        val xref = out.size()
        put("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { put("%010d 00000 n \n".format(it)) }
        val encrypt = if (password != null) " /Encrypt ${objects.size} 0 R" else ""
        put("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R$encrypt /ID [<${hex(ID)}> <${hex(ID)}>] >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private fun padded(password: String) = (password.toByteArray(Charsets.ISO_8859_1) + PAD).copyOf(32)

    private fun md5(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("MD5").run {
        parts.forEach { update(it) }
        digest()
    }

    /** Algorithm 3: O, from the owner password (here the same as the user's). */
    private fun ownerValue(password: String): ByteArray = rc4(md5(padded(password)).copyOf(5), padded(password))

    /** Algorithm 2: the 40-bit file key. */
    private fun fileKey(password: String): ByteArray {
        val p = PERMISSIONS
        val permissions = byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte())
        return md5(padded(password), ownerValue(password), permissions, ID).copyOf(5)
    }

    /** Algorithm 1: object [num]'s key (generation 0). */
    private fun objectKey(key: ByteArray, num: Int): ByteArray =
        md5(key, byteArrayOf(num.toByte(), (num shr 8).toByte(), (num shr 16).toByte(), 0, 0)).copyOf(key.size + 5)

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
            s[i] = s[j].also { s[j] = s[i] }
        }
        var a = 0
        var b = 0
        return ByteArray(data.size) { n ->
            a = (a + 1) and 0xff
            b = (b + s[a]) and 0xff
            s[a] = s[b].also { s[b] = s[a] }
            (data[n].toInt() xor s[(s[a] + s[b]) and 0xff]).toByte()
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
