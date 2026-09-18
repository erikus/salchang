package dev.estaab.salchang.ssh

/** Matches the first `https://` URL in free text; stops at whitespace and common quoting characters. */
private val HTTPS_URL_REGEX: Regex = Regex("""https://[^\s"'<>]+""")

/** Trailing punctuation that is part of the sentence rather than the URL. */
private val URL_TRAILING_PUNCTUATION: CharArray = charArrayOf('.', ',', ';', ':', ')', ']')

/**
 * The first `https://` URL in an SSH auth banner (e.g. the Tailscale SSH check-mode approval
 * link), or null if there is none. Pure.
 */
fun extractHttpsUrl(banner: String): String? {
    val match: MatchResult = HTTPS_URL_REGEX.find(banner) ?: return null
    return match.value.trimEnd(*URL_TRAILING_PUNCTUATION).takeIf { it.length > "https://".length }
}
