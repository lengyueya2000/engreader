package com.engreader.app.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBlogTest {

    @Test
    fun `a dash-live suffix is recognised`() {
        assertTrue(LiveBlog.isLive("Bolsonaro trial – live"))
        assertTrue(LiveBlog.isLive("Middle East crisis — live"))
    }

    @Test
    fun `latest updates and live updates are recognised`() {
        assertTrue(
            LiveBlog.isLive("Christa Pike's lawyers in court after she regains consciousness in hospital – latest updates"),
        )
        assertTrue(LiveBlog.isLive("Storm tracker: live updates"))
        assertTrue(LiveBlog.isLive("Budget 2026 as it happened"))
    }

    @Test
    fun `a trailing live word is recognised`() {
        assertTrue(LiveBlog.isLive("Minister denies police brutality across France – Europe live"))
        assertTrue(LiveBlog.isLive("Election results live"))
    }

    @Test
    fun `a live path in the url is recognised`() {
        assertTrue(LiveBlog.isLive("Some headline", "https://example.com/live/2026/oct/07/thing"))
        assertTrue(LiveBlog.isLive("Some headline", "https://example.com/news/live-blog-123"))
    }

    @Test
    fun `an ordinary report is not flagged`() {
        assertFalse(LiveBlog.isLive("Kenya reports first ever Ebola death, health ministry says"))
        assertFalse(LiveBlog.isLive("Skull fractures suggest servants were sacrificed"))
        assertFalse(LiveBlog.isLive("Pride and Prejudice — Chapter I", "gutenberg://pg1342.txt/x"))
    }

    @Test
    fun `a word merely containing live is not flagged`() {
        assertFalse(LiveBlog.isLive("Delivered: how the parcel network works"))
        assertFalse(LiveBlog.isLive("Olivier awards return to London"))
    }
}
