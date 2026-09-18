package dev.estaab.salchang.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthBannerUrlTest {
    @Test
    fun findsTailscaleCheckModeUrl() {
        val banner = "# Tailscale SSH requires an additional check.\n" +
            "# To authenticate, visit: https://login.tailscale.com/a/0123abcd\n"
        assertEquals("https://login.tailscale.com/a/0123abcd", extractHttpsUrl(banner))
    }

    @Test
    fun stripsTrailingPunctuation() {
        assertEquals("https://example.com/x", extractHttpsUrl("visit https://example.com/x."))
        assertEquals("https://example.com/x", extractHttpsUrl("(see https://example.com/x)"))
    }

    @Test
    fun ignoresPlainHttpAndEmptyText() {
        assertNull(extractHttpsUrl("visit http://example.com"))
        assertNull(extractHttpsUrl(""))
        assertNull(extractHttpsUrl("https://"))
    }

    @Test
    fun returnsFirstUrlOnly() {
        assertEquals("https://a.example", extractHttpsUrl("https://a.example and https://b.example"))
    }
}
