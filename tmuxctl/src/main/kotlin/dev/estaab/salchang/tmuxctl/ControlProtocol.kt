package dev.estaab.salchang.tmuxctl

import java.io.ByteArrayOutputStream

/** Prefix of every tmux control-mode notification / block marker line. */
private const val NOTIFICATION_PREFIX: Char = '%'

/** Separator between the argument list and the value in `%subscription-changed` / `%extended-output`. */
private const val VALUE_SEPARATOR_TOKEN: String = ":"

/** Placeholder tmux prints for an absent id/index in `%subscription-changed`. */
private const val ABSENT_FIELD: String = "-"

/** Number of octal digits tmux uses when escaping a byte in `%output`. */
private const val OCTAL_ESCAPE_DIGITS: Int = 3

private const val OUTPUT_PREFIX: String = "%output "
private const val EXTENDED_OUTPUT_PREFIX: String = "%extended-output "

/**
 * One line of a tmux control-mode conversation, parsed. Notification and block
 * marker lines start with `%`; anything between `%begin` and `%end`/`%error`
 * is a [Body] line belonging to a command reply.
 *
 * All fields are exactly what tmux wrote; nothing is interpreted beyond
 * splitting on the documented separators.
 */
sealed interface ControlLine {
    /** `%begin time number flags` - start of a command reply block. */
    data class Begin(val time: Long, val number: Long, val flags: Int) : ControlLine

    /** `%end time number flags` - successful end of a reply block. */
    data class End(val time: Long, val number: Long, val flags: Int) : ControlLine

    /** `%error time number flags` - failed end of a reply block. */
    data class Error(val time: Long, val number: Long, val flags: Int) : ControlLine

    /** A line inside a `%begin`..`%end`/`%error` block (command output). */
    data class Body(val line: String) : ControlLine

    /** `%output %N data` - bytes the pane's program wrote, already unescaped. */
    class Output(val paneId: String, val data: ByteArray) : ControlLine {
        override fun equals(other: Any?): Boolean =
            other is Output && other.paneId == paneId && other.data.contentEquals(data)

        override fun hashCode(): Int = 31 * paneId.hashCode() + data.contentHashCode()
        override fun toString(): String = "Output(paneId=$paneId, data=${data.size} bytes)"
    }

    /** `%extended-output %N age ... : data` - like [Output] with the age (ms) tmux held the data. */
    class ExtendedOutput(val paneId: String, val ageMs: Long, val data: ByteArray) : ControlLine {
        override fun equals(other: Any?): Boolean =
            other is ExtendedOutput && other.paneId == paneId && other.ageMs == ageMs &&
                other.data.contentEquals(data)

        override fun hashCode(): Int = 31 * (31 * paneId.hashCode() + ageMs.hashCode()) + data.contentHashCode()
        override fun toString(): String = "ExtendedOutput(paneId=$paneId, ageMs=$ageMs, data=${data.size} bytes)"
    }

    /**
     * `%subscription-changed name $S @W idx %P : value`. For window subscriptions the
     * pane is `-`; for session subscriptions window, index and pane are all `-`.
     * Absent fields are `null` here. [value] is everything after `: ` verbatim.
     */
    data class SubscriptionChanged(
        val name: String,
        val sessionId: String,
        val windowId: String?,
        val windowIndex: Int?,
        val paneId: String?,
        val value: String,
    ) : ControlLine

    /** `%layout-change @W layout visible-layout flags` ([flags] may be empty). */
    data class LayoutChange(
        val windowId: String,
        val layout: String,
        val visibleLayout: String,
        val flags: String,
    ) : ControlLine

    data class WindowAdd(val windowId: String) : ControlLine
    data class WindowClose(val windowId: String) : ControlLine
    data class WindowRenamed(val windowId: String, val name: String) : ControlLine
    data class WindowPaneChanged(val windowId: String, val paneId: String) : ControlLine
    data class SessionChanged(val sessionId: String, val name: String) : ControlLine
    data class SessionWindowChanged(val sessionId: String, val windowId: String) : ControlLine
    data object SessionsChanged : ControlLine
    data class SessionRenamed(val name: String) : ControlLine
    data class UnlinkedWindowAdd(val windowId: String) : ControlLine
    data class UnlinkedWindowClose(val windowId: String) : ControlLine
    data class UnlinkedWindowRenamed(val windowId: String, val name: String) : ControlLine
    data class PaneModeChanged(val paneId: String) : ControlLine
    data class Pause(val paneId: String) : ControlLine
    data class Continue(val paneId: String) : ControlLine
    data class Message(val text: String) : ControlLine
    data class ConfigError(val text: String) : ControlLine
    data class ClientDetached(val client: String) : ControlLine
    data class ClientSessionChanged(val client: String, val sessionId: String, val name: String) : ControlLine

    /** `%exit [reason]` - tmux is about to close the control connection. */
    data class Exit(val reason: String?) : ControlLine

    /** A `%`-line we could not parse (unknown notification or malformed arguments). */
    data class Unknown(val raw: String) : ControlLine
}

/**
 * Parses one raw line (without the trailing newline) of control-mode output.
 *
 * Use this overload when you have the raw bytes: `%output` payloads are decoded
 * byte-exactly (bytes >= 0x80 pass through untouched even if they are not valid
 * UTF-8, e.g. a multi-byte character split across two `%output` lines). All other
 * lines are decoded as UTF-8.
 *
 * @param insideBlock true if a `%begin` has been seen without its `%end`/`%error`;
 *   then every line that is not a block terminator is returned as [ControlLine.Body].
 */
fun parseControlLine(line: ByteArray, insideBlock: Boolean): ControlLine {
    if (startsWithAscii(line, OUTPUT_PREFIX)) {
        return parseOutputLine(line, OUTPUT_PREFIX.length) ?: ControlLine.Unknown(String(line, Charsets.UTF_8))
    }
    if (startsWithAscii(line, EXTENDED_OUTPUT_PREFIX)) {
        return parseExtendedOutputLine(line, EXTENDED_OUTPUT_PREFIX.length)
            ?: ControlLine.Unknown(String(line, Charsets.UTF_8))
    }
    return parseTextLine(String(line, Charsets.UTF_8), insideBlock)
}

/**
 * Parses one line of control-mode output given as a string. Characters in
 * `%output` payloads are re-encoded as UTF-8 before unescaping; prefer the
 * [ByteArray] overload on the live connection.
 */
fun parseControlLine(line: String, insideBlock: Boolean): ControlLine =
    parseControlLine(line.toByteArray(Charsets.UTF_8), insideBlock)

/**
 * Decodes the payload of an `%output` line: `\ooo` (three octal digits) stands
 * for one byte (tmux escapes every byte < 0x20 and the backslash this way);
 * every other character is emitted as its UTF-8 bytes.
 */
fun unescapeOutput(s: String): ByteArray = unescapeOutputBytes(s.toByteArray(Charsets.UTF_8), 0)

/** Encodes bytes for `send-keys -H`: two lowercase hex digits per byte, space separated. */
fun hexEncode(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 3)
    for (i in bytes.indices) {
        if (i > 0) sb.append(' ')
        val v = bytes[i].toInt() and 0xff
        sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
    }
    return sb.toString()
}

private const val HEX_DIGITS: String = "0123456789abcdef"

private fun unescapeOutputBytes(bytes: ByteArray, from: Int): ByteArray {
    val out = ByteArrayOutputStream(bytes.size - from)
    var i = from
    while (i < bytes.size) {
        val b = bytes[i]
        if (b == '\\'.code.toByte() && i + OCTAL_ESCAPE_DIGITS < bytes.size &&
            isOctal(bytes[i + 1]) && isOctal(bytes[i + 2]) && isOctal(bytes[i + 3])
        ) {
            val value = ((bytes[i + 1] - '0'.code.toByte()) shl 6) or
                ((bytes[i + 2] - '0'.code.toByte()) shl 3) or
                (bytes[i + 3] - '0'.code.toByte())
            out.write(value and 0xff)
            i += 1 + OCTAL_ESCAPE_DIGITS
        } else {
            out.write(b.toInt())
            i++
        }
    }
    return out.toByteArray()
}

private fun isOctal(b: Byte): Boolean = b >= '0'.code.toByte() && b <= '7'.code.toByte()

private fun startsWithAscii(bytes: ByteArray, prefix: String): Boolean {
    if (bytes.size < prefix.length) return false
    for (i in prefix.indices) if (bytes[i] != prefix[i].code.toByte()) return false
    return true
}

/** Index of the next space at or after [from], or `bytes.size`. */
private fun indexOfSpace(bytes: ByteArray, from: Int): Int {
    var i = from
    while (i < bytes.size && bytes[i] != ' '.code.toByte()) i++
    return i
}

private fun asciiToken(bytes: ByteArray, from: Int, to: Int): String = String(bytes, from, to - from, Charsets.US_ASCII)

/** `%output %N data` */
private fun parseOutputLine(line: ByteArray, from: Int): ControlLine.Output? {
    val paneEnd = indexOfSpace(line, from)
    val paneId = asciiToken(line, from, paneEnd)
    if (!isPaneId(paneId)) return null
    // Data may be empty (line ends right after the pane id); otherwise skip the single space.
    val dataStart = if (paneEnd < line.size) paneEnd + 1 else paneEnd
    return ControlLine.Output(paneId, unescapeOutputBytes(line, dataStart))
}

/** `%extended-output %N age [args...] : data` */
private fun parseExtendedOutputLine(line: ByteArray, from: Int): ControlLine.ExtendedOutput? {
    val paneEnd = indexOfSpace(line, from)
    val paneId = asciiToken(line, from, paneEnd)
    if (!isPaneId(paneId) || paneEnd >= line.size) return null
    val ageStart = paneEnd + 1
    val ageEnd = indexOfSpace(line, ageStart)
    val ageMs = asciiToken(line, ageStart, ageEnd).toLongOrNull() ?: return null
    // Skip any further space-separated arguments until the lone ':' token.
    var i = ageEnd
    while (i < line.size) {
        val tokenStart = i + 1
        val tokenEnd = indexOfSpace(line, tokenStart)
        if (tokenEnd - tokenStart == 1 && line[tokenStart] == ':'.code.toByte()) {
            val dataStart = if (tokenEnd < line.size) tokenEnd + 1 else tokenEnd
            return ControlLine.ExtendedOutput(paneId, ageMs, unescapeOutputBytes(line, dataStart))
        }
        i = tokenEnd
    }
    return null
}

private fun parseTextLine(line: String, insideBlock: Boolean): ControlLine {
    if (line.isEmpty() || line[0] != NOTIFICATION_PREFIX) {
        return if (insideBlock) ControlLine.Body(line) else ControlLine.Unknown(line)
    }
    val space = line.indexOf(' ')
    val keyword = if (space < 0) line else line.substring(0, space)
    val rest = if (space < 0) "" else line.substring(space + 1)

    if (insideBlock && keyword != "%end" && keyword != "%error" && keyword != "%begin") {
        return ControlLine.Body(line)
    }

    return when (keyword) {
        "%begin" -> parseBlockMarker(rest) { t, n, f -> ControlLine.Begin(t, n, f) }
        "%end" -> parseBlockMarker(rest) { t, n, f -> ControlLine.End(t, n, f) }
        "%error" -> parseBlockMarker(rest) { t, n, f -> ControlLine.Error(t, n, f) }
        "%subscription-changed" -> parseSubscriptionChanged(rest)
        "%layout-change" -> parseLayoutChange(rest)
        "%window-add" -> singleId(rest, ::isWindowId) { ControlLine.WindowAdd(it) }
        "%window-close" -> singleId(rest, ::isWindowId) { ControlLine.WindowClose(it) }
        "%window-renamed" -> idAndText(rest, ::isWindowId) { id, text -> ControlLine.WindowRenamed(id, text) }
        "%window-pane-changed" -> twoIds(rest, ::isWindowId, ::isPaneId) { w, p -> ControlLine.WindowPaneChanged(w, p) }
        "%session-changed" -> idAndText(rest, ::isSessionId) { id, text -> ControlLine.SessionChanged(id, text) }
        "%session-window-changed" -> twoIds(rest, ::isSessionId, ::isWindowId) { s, w -> ControlLine.SessionWindowChanged(s, w) }
        "%sessions-changed" -> ControlLine.SessionsChanged
        "%session-renamed" -> ControlLine.SessionRenamed(rest)
        "%unlinked-window-add" -> singleId(rest, ::isWindowId) { ControlLine.UnlinkedWindowAdd(it) }
        "%unlinked-window-close" -> singleId(rest, ::isWindowId) { ControlLine.UnlinkedWindowClose(it) }
        "%unlinked-window-renamed" -> idAndText(rest, ::isWindowId) { id, text -> ControlLine.UnlinkedWindowRenamed(id, text) }
        "%pane-mode-changed" -> singleId(rest, ::isPaneId) { ControlLine.PaneModeChanged(it) }
        "%pause" -> singleId(rest, ::isPaneId) { ControlLine.Pause(it) }
        "%continue" -> singleId(rest, ::isPaneId) { ControlLine.Continue(it) }
        "%message" -> ControlLine.Message(rest)
        "%config-error" -> ControlLine.ConfigError(rest)
        "%client-detached" -> ControlLine.ClientDetached(rest)
        "%client-session-changed" -> parseClientSessionChanged(rest)
        "%exit" -> ControlLine.Exit(if (space < 0) null else rest)
        else -> ControlLine.Unknown(line)
    } ?: ControlLine.Unknown(line)
}

private inline fun parseBlockMarker(rest: String, build: (Long, Long, Int) -> ControlLine): ControlLine? {
    val parts = rest.split(' ')
    if (parts.size != 3) return null
    val time = parts[0].toLongOrNull() ?: return null
    val number = parts[1].toLongOrNull() ?: return null
    val flags = parts[2].toIntOrNull() ?: return null
    return build(time, number, flags)
}

private inline fun singleId(rest: String, valid: (String) -> Boolean, build: (String) -> ControlLine): ControlLine? =
    if (valid(rest)) build(rest) else null

private inline fun idAndText(
    rest: String,
    valid: (String) -> Boolean,
    build: (String, String) -> ControlLine,
): ControlLine? {
    val space = rest.indexOf(' ')
    val id = if (space < 0) rest else rest.substring(0, space)
    if (!valid(id)) return null
    val text = if (space < 0) "" else rest.substring(space + 1)
    return build(id, text)
}

private inline fun twoIds(
    rest: String,
    validFirst: (String) -> Boolean,
    validSecond: (String) -> Boolean,
    build: (String, String) -> ControlLine,
): ControlLine? {
    val parts = rest.split(' ')
    if (parts.size != 2 || !validFirst(parts[0]) || !validSecond(parts[1])) return null
    return build(parts[0], parts[1])
}

/** `name $S @W idx %P [args...] : value` (window: `@W idx - `, session: `- - -`). */
private fun parseSubscriptionChanged(rest: String): ControlLine? {
    val tokens = rest.split(' ')
    if (tokens.size < 6) return null
    val name = tokens[0]
    val sessionId = tokens[1]
    if (!isSessionId(sessionId)) return null
    val windowId = tokens[2].takeIf { it != ABSENT_FIELD }
    if (windowId != null && !isWindowId(windowId)) return null
    val windowIndex = tokens[3].takeIf { it != ABSENT_FIELD }?.let { it.toIntOrNull() ?: return null }
    val paneId = tokens[4].takeIf { it != ABSENT_FIELD }
    if (paneId != null && !isPaneId(paneId)) return null

    // Skip extra args until the lone ':' token; value is everything after ": " verbatim.
    var offset = 0
    for (i in 0 until 5) offset += tokens[i].length + 1
    var i = 5
    while (i < tokens.size) {
        val token = tokens[i]
        if (token == VALUE_SEPARATOR_TOKEN) {
            val valueStart = offset + token.length + 1
            val value = if (valueStart <= rest.length) rest.substring(valueStart) else ""
            return ControlLine.SubscriptionChanged(name, sessionId, windowId, windowIndex, paneId, value)
        }
        offset += token.length + 1
        i++
    }
    return null
}

/** `@W layout visible-layout flags` where flags may be the empty string. */
private fun parseLayoutChange(rest: String): ControlLine? {
    val parts = rest.split(' ', limit = 4)
    if (parts.size < 3 || !isWindowId(parts[0])) return null
    val flags = if (parts.size == 4) parts[3] else ""
    return ControlLine.LayoutChange(parts[0], parts[1], parts[2], flags)
}

/** `client $S name` */
private fun parseClientSessionChanged(rest: String): ControlLine? {
    val parts = rest.split(' ', limit = 3)
    if (parts.size < 2 || !isSessionId(parts[1])) return null
    return ControlLine.ClientSessionChanged(parts[0], parts[1], if (parts.size == 3) parts[2] else "")
}

private fun isPrefixedNumber(s: String, prefix: Char): Boolean =
    s.length >= 2 && s[0] == prefix && s.substring(1).all { it in '0'..'9' }

/** True for a tmux pane id such as `%12`. */
fun isPaneId(s: String): Boolean = isPrefixedNumber(s, '%')

/** True for a tmux window id such as `@3`. */
fun isWindowId(s: String): Boolean = isPrefixedNumber(s, '@')

/** True for a tmux session id such as `$1`. */
fun isSessionId(s: String): Boolean = isPrefixedNumber(s, '$')
