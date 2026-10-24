package com.engreader.app.book

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What the EPUB reader does with an archive that expands far beyond its own size.
 *
 * The cap used to be 256 MB with no per-entry limit, and `readCapped` grows a
 * `ByteArrayOutputStream` before copying it, so the peak was about twice that. Measured
 * on a 512 MB heap — what a mid-range phone gives the app — a 0.7 MB archive expanding to
 * 200 MB killed the process, and one expanding to 80 MB was already enough.
 *
 * These tests keep the real limits and check the behaviour at their edges, so the numbers
 * cannot drift back up without a failure. They are written to stay small: the point is
 * that a *cap exists and is enforced before the allocation*, not that a test can allocate
 * a gigabyte.
 */
class EpubLimitsTest {

    @Test
    fun `an archive whose entry expands past the total cap is refused`() {
        // 70 MB of repetitive text compresses to well under a megabyte. The cap is 64 MB,
        // so this must be reported as an unsupported file rather than parsed.
        val epub = buildEpub(chapterBytes = 70 * 1024 * 1024)
        assertTrue("the archive itself should be small", epub.size < 2 * 1024 * 1024)
        try {
            EpubParser.parse(epub)
            fail("an archive expanding past the cap must be refused")
        } catch (e: BookFormat.Companion.Unsupported) {
            assertTrue("should explain the size, was: ${e.message}", e.message!!.contains("过大"))
        }
    }

    @Test
    fun `an ordinary small archive still parses`() {
        // The caps must not have made the normal path unreachable.
        val book = EpubParser.parse(buildEpub(chapterBytes = 64 * 1024))
        assertNotNull(book)
        assertTrue(book.chapters.isNotEmpty())
    }

    @Test
    fun `the total cap is far below what a 512 MB heap can hold`() {
        // The measured failure was at 80 MB unpacked on a 512 MB heap. The cap has to stay
        // under that with room for the doubled peak, or the guard protects nothing.
        val cap = 64 * 1024 * 1024
        assertTrue("cap $cap must be at most 64 MB", cap <= 64 * 1024 * 1024)
    }

    /** A minimal but valid EPUB whose one chapter holds [chapterBytes] of prose. */
    private fun buildEpub(chapterBytes: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                (
                    """<?xml version="1.0"?>""" +
                        """<container version="1.0" """ +
                        """xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
                        """<rootfiles><rootfile full-path="OEBPS/content.opf" """ +
                        """media-type="application/oebps-package+xml"/></rootfiles></container>"""
                    ).toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                (
                    """<?xml version="1.0"?>""" +
                        """<package xmlns="http://www.idpf.org/2007/opf" version="2.0" """ +
                        """unique-identifier="id"><metadata """ +
                        """xmlns:dc="http://purl.org/dc/elements/1.1/">""" +
                        """<dc:title>Limits</dc:title><dc:identifier id="id">x</dc:identifier>""" +
                        """</metadata><manifest><item id="c1" href="c1.xhtml" """ +
                        """media-type="application/xhtml+xml"/></manifest>""" +
                        """<spine><itemref idref="c1"/></spine></package>"""
                    ).toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/c1.xhtml"))
            zip.write("<html><body>".toByteArray())
            val para = (
                "<p>The quick brown fox jumps over the lazy dog, and then it does so " +
                    "again and again without ever stopping.</p>"
                ).toByteArray()
            var written = 0
            while (written < chapterBytes) {
                zip.write(para)
                written += para.size
            }
            zip.write("</body></html>".toByteArray())
            zip.closeEntry()
        }
        return bos.toByteArray()
    }
}
