package dev.estaab.salchang.tmuxctl

import java.io.ByteArrayOutputStream

/** Key table tmux consults for the key typed after the prefix. */
const val PREFIX_TABLE: String = "prefix"

/** Key table tmux consults for keys typed without the prefix (`bind -n`). */
const val ROOT_TABLE: String = "root"

/** The tmux command that types the prefix key itself into the pane; handled locally by [TmuxKeyRouter]. */
private const val SEND_PREFIX_COMMAND: String = "send-prefix"

private const val ESC: Byte = 0x1b
private const val ESC_CHAR: Char = ''
private const val CSI_INTRODUCER: Byte = '['.code.toByte()
private const val SS3_INTRODUCER: Byte = 'O'.code.toByte()
private const val CSI: String = "["
private const val SS3: String = "O"
private const val CSI_PARAM_SEPARATOR: Char = ';'
private const val CSI_TILDE_FINAL: Char = '~'
private const val BTAB_FINAL: Char = 'Z'

/** CSI parameter and intermediate bytes (ECMA-48 5.4): 0x20..0x3f. */
private const val CSI_PARAMETER_MIN: Int = 0x20
private const val CSI_PARAMETER_MAX: Int = 0x3f

/** CSI final bytes: 0x40..0x7e. */
private const val CSI_FINAL_MIN: Int = 0x40
private const val CSI_FINAL_MAX: Int = 0x7e

/** xterm modifier parameter bits (`CSI 1;m X` has m = 1 + bits). */
private const val XTERM_MODIFIER_SHIFT: Int = 1
private const val XTERM_MODIFIER_META: Int = 2
private const val XTERM_MODIFIER_CTRL: Int = 4
private const val XTERM_MODIFIER_BASE: Int = 1

/** Modifier prefixes as tmux prints them, in tmux's order (`C-M-S-x`). */
private const val CTRL_PREFIX: String = "C-"
private const val META_PREFIX: String = "M-"
private const val SHIFT_PREFIX: String = "S-"

private const val CTRL_SPACE_BYTE: Int = 0x00
private const val TAB_BYTE: Int = 0x09
private const val ENTER_BYTE: Int = 0x0d
private const val ESCAPE_BYTE: Int = 0x1b
private const val SPACE_BYTE: Int = 0x20
private const val DELETE_BYTE: Int = 0x7f
private const val CTRL_LETTER_MIN: Int = 0x01
private const val CTRL_LETTER_MAX: Int = 0x1a
private const val PRINTABLE_MIN: Int = 0x21
private const val PRINTABLE_MAX: Int = 0x7e

/** `CSI 1 ~` / `CSI 4 ~` (vt220 style) and `CSI 7 ~` / `CSI 8 ~` (rxvt) are Home / End too. */
private val TILDE_ALIASES: Map<Int, String> = mapOf(1 to "Home", 4 to "End", 7 to "Home", 8 to "End")

/** Control characters outside `C-a`..`C-z` and how tmux names them (`C-@` is printed as `C-Space`). */
private val CTRL_PUNCTUATION: Map<Char, Int> = mapOf(
    '@' to 0x00, '[' to 0x1b, '\\' to 0x1c, ']' to 0x1d, '^' to 0x1e, '_' to 0x1f, '?' to 0x7f,
)

/** Names tmux accepts for keys it prints under another name (`list-keys` prints the canonical one). */
private val KEY_NAME_ALIASES: Map<String, String> = mapOf(
    "PageUp" to "PPage", "PgUp" to "PPage",
    "PageDown" to "NPage", "PgDn" to "NPage",
    "Insert" to "IC", "Delete" to "DC",
)

/** One line of `tmux list-keys` output; [command] is in control-mode command-line syntax. */
data class TmuxKeyBinding(val table: String, val key: String, val command: String, val repeat: Boolean)

/**
 * `bind-key [-r] -T <table> <key> <command...>` as printed by `list-keys`. The key token is
 * either backslash-escaped (`\"`, `\#`, `\;`, `\\`...) or wrapped in single/double quotes.
 */
private val BIND_KEY_LINE: Regex = Regex("""^bind-key\s+(?:(-r)\s+)?-T\s+(\S+)\s+(\S+)\s+(.*\S)\s*$""")

/**
 * `list-keys` prints a bound command list as `bind-key` *arguments*, where a standalone `\;`
 * argument separates commands. A control-mode command line uses a bare `;` for that; `\;` on a
 * line means a literal semicolon argument.
 */
private val ESCAPED_COMMAND_SEPARATOR: Regex = Regex("""(?<=\s)\\;(?=\s|$)""")

/**
 * Parses `list-keys` output. Lines that do not look like a binding are skipped. Mouse keys
 * (`MouseDown1Pane`...) parse like any other key; [TmuxKeyCodes] just never matches them.
 */
fun parseListKeys(lines: List<String>): List<TmuxKeyBinding> {
    val result = ArrayList<TmuxKeyBinding>(lines.size)
    for (line in lines) {
        val match: MatchResult = BIND_KEY_LINE.find(line) ?: continue
        val (repeat: String, table: String, rawKey: String, command: String) = match.destructured
        val key: String = unescapeKeyToken(rawKey) ?: continue
        result.add(
            TmuxKeyBinding(
                table = table,
                key = key,
                command = ESCAPED_COMMAND_SEPARATOR.replace(command, ";"),
                repeat = repeat.isNotEmpty(),
            ),
        )
    }
    return result
}

/** Undoes tmux's argument quoting of a key token; null for an unterminated quote. */
private fun unescapeKeyToken(token: String): String? {
    if (token.length >= 2 && token.first() == '\'' && token.last() == '\'') return token.substring(1, token.length - 1)
    val quoted: Boolean = token.length >= 2 && token.first() == '"' && token.last() == '"'
    val body: String = if (quoted) token.substring(1, token.length - 1) else token
    val out = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c: Char = body[i]
        if (c == '\\' && i + 1 < body.length) {
            out.append(body[i + 1])
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/**
 * Maps tmux key names (`C-n`, `M-Left`, `PPage`, `F5`, `é`...) to the bytes a terminal sends
 * for them and back. Meta is encoded as an ESC prefix; on input both that form and the xterm
 * `CSI 1;m X` modifier form are recognised, as are the SS3 (application cursor mode) arrows.
 */
object TmuxKeyCodes {
    /** A named key's escape sequence and how xterm-style modifiers attach to it. */
    private sealed interface Encoding {
        /** [modifierBits] is an [XTERM_MODIFIER_SHIFT]/[XTERM_MODIFIER_CTRL] mask; null if unsupported. */
        fun encode(modifierBits: Int): String?
    }

    /** `CSI <final>`; with modifiers `CSI 1;m <final>`. */
    private class CsiLetter(val final: Char) : Encoding {
        override fun encode(modifierBits: Int): String =
            if (modifierBits == 0) "$CSI$final" else "$CSI${XTERM_MODIFIER_BASE}$CSI_PARAM_SEPARATOR${XTERM_MODIFIER_BASE + modifierBits}$final"
    }

    /** `SS3 <final>` (F1-F4); with modifiers `CSI 1;m <final>`. */
    private class Ss3Letter(val final: Char) : Encoding {
        override fun encode(modifierBits: Int): String =
            if (modifierBits == 0) "$SS3$final" else "$CSI${XTERM_MODIFIER_BASE}$CSI_PARAM_SEPARATOR${XTERM_MODIFIER_BASE + modifierBits}$final"
    }

    /** `CSI <number> ~`; with modifiers `CSI <number>;m ~`. */
    private class CsiTilde(val number: Int) : Encoding {
        override fun encode(modifierBits: Int): String =
            if (modifierBits == 0) "$CSI$number$CSI_TILDE_FINAL" else "$CSI$number$CSI_PARAM_SEPARATOR${XTERM_MODIFIER_BASE + modifierBits}$CSI_TILDE_FINAL"
    }

    /** Fixed bytes; only `C-Space` takes a modifier. */
    private class Literal(val text: String, val withCtrl: String? = null) : Encoding {
        override fun encode(modifierBits: Int): String? = when (modifierBits) {
            0 -> text
            XTERM_MODIFIER_CTRL -> withCtrl
            else -> null
        }
    }

    private val NAMED_KEYS: Map<String, Encoding> = mapOf(
        "Up" to CsiLetter('A'),
        "Down" to CsiLetter('B'),
        "Right" to CsiLetter('C'),
        "Left" to CsiLetter('D'),
        "Home" to CsiLetter('H'),
        "End" to CsiLetter('F'),
        "F1" to Ss3Letter('P'),
        "F2" to Ss3Letter('Q'),
        "F3" to Ss3Letter('R'),
        "F4" to Ss3Letter('S'),
        "F5" to CsiTilde(15),
        "F6" to CsiTilde(17),
        "F7" to CsiTilde(18),
        "F8" to CsiTilde(19),
        "F9" to CsiTilde(20),
        "F10" to CsiTilde(21),
        "F11" to CsiTilde(23),
        "F12" to CsiTilde(24),
        "IC" to CsiTilde(2),
        "DC" to CsiTilde(3),
        "PPage" to CsiTilde(5),
        "NPage" to CsiTilde(6),
        "BTab" to Literal("$CSI$BTAB_FINAL"),
        "Space" to Literal(" ", withCtrl = " "),
        "Enter" to Literal("\r"),
        "Tab" to Literal("\t"),
        "BSpace" to Literal(""),
        "Escape" to Literal(ESC_CHAR.toString()),
    )

    private val LETTER_KEY_NAMES: Map<Char, String> = NAMED_KEYS.entries
        .mapNotNull { (name, enc) ->
            when (enc) {
                is CsiLetter -> enc.final to name
                is Ss3Letter -> enc.final to name
                else -> null
            }
        }.toMap()

    private val TILDE_KEY_NAMES: Map<Int, String> =
        NAMED_KEYS.entries.mapNotNull { (name, enc) -> (enc as? CsiTilde)?.let { it.number to name } }.toMap() + TILDE_ALIASES

    /** A key as tmux names it: modifiers plus a base name (`Left`, `PPage`, `a`, `é`). */
    private data class Key(val ctrl: Boolean, val meta: Boolean, val shift: Boolean, val base: String) {
        fun name(): String = buildString {
            if (ctrl) append(CTRL_PREFIX)
            if (meta) append(META_PREFIX)
            if (shift) append(SHIFT_PREFIX)
            append(base)
        }
    }

    /** The bytes a terminal sends for tmux key [name]; null if the name is unknown or not a keyboard key. */
    fun bytesForKey(name: String): ByteArray? {
        var ctrl = false
        var meta = false
        var shift = false
        var rest: String = name
        while (rest.length > 2) {
            when {
                rest.startsWith(CTRL_PREFIX) -> ctrl = true
                rest.startsWith(META_PREFIX) -> meta = true
                rest.startsWith(SHIFT_PREFIX) -> shift = true
                else -> break
            }
            rest = rest.substring(2)
        }
        if (rest.isEmpty()) return null
        val base: String = KEY_NAME_ALIASES[rest] ?: rest
        val modifierBits: Int = (if (shift) XTERM_MODIFIER_SHIFT else 0) or (if (ctrl) XTERM_MODIFIER_CTRL else 0)
        val encoded: String = NAMED_KEYS[base]?.encode(modifierBits) ?: encodeCharacter(base, ctrl, shift) ?: return null
        return (if (meta) ESC_CHAR + encoded else encoded).toByteArray(Charsets.UTF_8)
    }

    private fun encodeCharacter(base: String, ctrl: Boolean, shift: Boolean): String? {
        if (shift || base.codePointCount(0, base.length) != 1) return null
        if (!ctrl) return base
        val c: Char = base[0]
        val code: Int = when {
            c in 'a'..'z' -> c - 'a' + CTRL_LETTER_MIN
            c in 'A'..'Z' -> c - 'A' + CTRL_LETTER_MIN
            else -> CTRL_PUNCTUATION[c] ?: return null
        }
        return code.toChar().toString()
    }

    /** The tmux name of the key a terminal sent as [bytes]; null if [bytes] is not one key. */
    fun keyForBytes(bytes: ByteArray): String? = decode(bytes)?.name()

    private fun decode(bytes: ByteArray): Key? {
        if (bytes.isEmpty()) return null
        if (bytes[0] != ESC || bytes.size == 1) return decodeCharacter(bytes)
        decodeSequence(bytes)?.let { return it }
        val inner: Key = decode(bytes.copyOfRange(1, bytes.size)) ?: return null
        return inner.copy(meta = true)
    }

    private fun decodeCharacter(bytes: ByteArray): Key? {
        if (bytes.size == 1) {
            val b: Int = bytes[0].toInt() and 0xff
            return when {
                b == CTRL_SPACE_BYTE -> Key(ctrl = true, meta = false, shift = false, base = "Space")
                b == TAB_BYTE -> plain("Tab")
                b == ENTER_BYTE -> plain("Enter")
                b == ESCAPE_BYTE -> plain("Escape")
                b == SPACE_BYTE -> plain("Space")
                b == DELETE_BYTE -> plain("BSpace")
                b in CTRL_LETTER_MIN..CTRL_LETTER_MAX -> Key(ctrl = true, meta = false, shift = false, base = ('a' + (b - CTRL_LETTER_MIN)).toString())
                b in PRINTABLE_MIN..PRINTABLE_MAX -> plain(b.toChar().toString())
                else -> CTRL_PUNCTUATION.entries.firstOrNull { it.value == b }?.let { Key(ctrl = true, meta = false, shift = false, base = it.key.toString()) }
            }
        }
        val text = String(bytes, Charsets.UTF_8)
        if (text.codePointCount(0, text.length) != 1 || !text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return null
        return plain(text)
    }

    private fun plain(base: String): Key = Key(ctrl = false, meta = false, shift = false, base = base)

    /** CSI (`ESC [ params final`) and SS3 (`ESC O final`) forms of the named keys. */
    private fun decodeSequence(bytes: ByteArray): Key? {
        if (bytes.size < 3) return null
        val final: Char = (bytes[bytes.size - 1].toInt() and 0xff).toChar()
        when (bytes[1]) {
            SS3_INTRODUCER -> {
                if (bytes.size != 3) return null
                return LETTER_KEY_NAMES[final]?.let { plain(it) }
            }
            CSI_INTRODUCER -> {
                if (bytes.size == 3 && final == BTAB_FINAL) return plain("BTab")
                val params: List<Int> = parseParams(bytes, 2, bytes.size - 1) ?: return null
                val (base: String, modifierBits: Int) = when {
                    final == CSI_TILDE_FINAL && params.size in 1..2 ->
                        Pair(TILDE_KEY_NAMES[params[0]] ?: return null, params.getOrElse(1) { XTERM_MODIFIER_BASE } - XTERM_MODIFIER_BASE)
                    final != CSI_TILDE_FINAL && params.isEmpty() -> Pair(LETTER_KEY_NAMES[final] ?: return null, 0)
                    final != CSI_TILDE_FINAL && params.size == 2 && params[0] == XTERM_MODIFIER_BASE ->
                        Pair(LETTER_KEY_NAMES[final] ?: return null, params[1] - XTERM_MODIFIER_BASE)
                    else -> return null
                }
                if (modifierBits < 0) return null
                return Key(
                    ctrl = modifierBits and XTERM_MODIFIER_CTRL != 0,
                    meta = modifierBits and XTERM_MODIFIER_META != 0,
                    shift = modifierBits and XTERM_MODIFIER_SHIFT != 0,
                    base = base,
                )
            }
            else -> return null
        }
    }

    /** Parses `n;m;...` between [from] and [to]; an empty range is an empty list; null on anything else. */
    private fun parseParams(bytes: ByteArray, from: Int, to: Int): List<Int>? {
        if (from >= to) return emptyList()
        val text = String(bytes, from, to - from, Charsets.US_ASCII)
        return text.split(CSI_PARAM_SEPARATOR).map { it.toIntOrNull() ?: return null }
    }
}

/**
 * Splits a chunk of bytes typed into the terminal into key tokens: one CSI (`ESC [` ... final)
 * or SS3 (`ESC O` x) sequence, one Meta key (ESC plus the following token, so `ESC ESC [ D` is
 * one token), a lone ESC at the end of the chunk, one UTF-8 character, or one byte.
 */
object TmuxKeyTokenizer {
    fun tokenize(bytes: ByteArray): List<ByteArray> {
        val tokens = ArrayList<ByteArray>()
        var start = 0
        while (start < bytes.size) {
            val end: Int = tokenEnd(bytes, start)
            tokens.add(bytes.copyOfRange(start, end))
            start = end
        }
        return tokens
    }

    /** Index one past the token that starts at [start]. */
    private fun tokenEnd(bytes: ByteArray, start: Int): Int {
        val n: Int = bytes.size
        if (bytes[start] != ESC) return minOf(n, start + utf8Length(bytes, start))
        if (start + 1 >= n) return n
        return when (bytes[start + 1]) {
            CSI_INTRODUCER -> {
                var i: Int = start + 2
                while (i < n && (bytes[i].toInt() and 0xff) in CSI_PARAMETER_MIN..CSI_PARAMETER_MAX) i++
                if (i < n && (bytes[i].toInt() and 0xff) in CSI_FINAL_MIN..CSI_FINAL_MAX) i + 1 else if (i == start + 2) start + 2 else n
            }
            SS3_INTRODUCER -> minOf(n, start + 3)
            ESC -> tokenEnd(bytes, start + 1)
            else -> minOf(n, start + 1 + utf8Length(bytes, start + 1))
        }
    }

    /** Length of the UTF-8 character starting at [index] if its bytes are well formed, else 1. */
    private fun utf8Length(bytes: ByteArray, index: Int): Int {
        val lead: Int = bytes[index].toInt() and 0xff
        val length: Int = when {
            lead < 0x80 -> 1
            lead in 0xc2..0xdf -> 2
            lead in 0xe0..0xef -> 3
            lead in 0xf0..0xf4 -> 4
            else -> return 1
        }
        if (index + length > bytes.size) return 1
        for (i in 1 until length) if ((bytes[index + i].toInt() and 0xc0) != 0x80) return 1
        return length
    }
}

/**
 * Client-side emulation of tmux's `prefix` and `root` key tables for keys that are sent with
 * `send-keys -H` (which bypasses tmux's key tables). Stateful: [armed] is true between the
 * prefix key and the next key, like tmux's own prefix state. Not thread-safe; use from one thread.
 *
 * Repeat (`-r`) bindings get no repeat timer: each press needs the prefix again.
 */
class TmuxKeyRouter(prefixKey: String, bindings: List<TmuxKeyBinding>) {
    sealed interface Action {
        /** Bytes to type into the pane. */
        class Send(val bytes: ByteArray) : Action {
            override fun equals(other: Any?): Boolean = other is Send && other.bytes.contentEquals(bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
            override fun toString(): String = "Send(${bytes.joinToString(" ") { "%02x".format(it) }})"
        }

        /** A bound command to run on the control channel. */
        data class Run(val binding: TmuxKeyBinding) : Action
    }

    private val prefixBytes: ByteArray? = TmuxKeyCodes.bytesForKey(prefixKey)

    /** Canonical prefix name; null (never arms) when the prefix is not a keyboard key, e.g. `None`. */
    private val prefixName: String? = prefixBytes?.let { TmuxKeyCodes.keyForBytes(it) }

    private val prefixTable: Map<String, TmuxKeyBinding> = table(bindings, PREFIX_TABLE)
    private val rootTable: Map<String, TmuxKeyBinding> = table(bindings, ROOT_TABLE)

    /** True after the prefix key was typed and before the key it applies to. */
    var armed: Boolean = false
        private set

    fun reset() {
        armed = false
    }

    /** Turns one chunk of typed bytes into actions; consecutive bytes to send are coalesced. */
    fun route(chunk: ByteArray): List<Action> {
        val actions = ArrayList<Action>()
        val pendingSend = ByteArrayOutputStream()
        fun flushSend() {
            if (pendingSend.size() > 0) {
                actions.add(Action.Send(pendingSend.toByteArray()))
                pendingSend.reset()
            }
        }
        fun run(binding: TmuxKeyBinding) {
            flushSend()
            actions.add(Action.Run(binding))
        }
        for (token in TmuxKeyTokenizer.tokenize(chunk)) {
            val name: String? = TmuxKeyCodes.keyForBytes(token)
            if (name == null) {
                // Not a key (e.g. an emulator reply such as a cursor position report): pass through.
                pendingSend.write(token)
                continue
            }
            if (armed) {
                armed = false
                val binding: TmuxKeyBinding? = prefixTable[name]
                when {
                    binding != null && binding.command == SEND_PREFIX_COMMAND -> pendingSend.write(prefixBytes ?: token)
                    binding != null -> run(binding)
                    name == prefixName -> pendingSend.write(token)
                    else -> Unit // tmux ignores an unbound key after the prefix.
                }
            } else if (name == prefixName) {
                armed = true
            } else {
                val binding: TmuxKeyBinding? = rootTable[name]
                if (binding != null) run(binding) else pendingSend.write(token)
            }
        }
        flushSend()
        return actions
    }

    private fun table(bindings: List<TmuxKeyBinding>, table: String): Map<String, TmuxKeyBinding> {
        val result = HashMap<String, TmuxKeyBinding>()
        for (binding in bindings) {
            if (binding.table != table) continue
            // Keyed by the name keyForBytes produces, so aliases (PgUp/PPage, C-@/C-Space) match typed keys.
            val canonical: String = TmuxKeyCodes.bytesForKey(binding.key)?.let { TmuxKeyCodes.keyForBytes(it) } ?: continue
            result.putIfAbsent(canonical, binding)
        }
        return result
    }
}
