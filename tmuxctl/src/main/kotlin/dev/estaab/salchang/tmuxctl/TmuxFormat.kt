package dev.estaab.salchang.tmuxctl

/**
 * A tmux style directive as it appears in expanded status-line text: `#[` up to the next `]`
 * (`#[fg=black,bold]`, `#[default]`, `#[align=left,fill=colour114]`). A style never contains `]`.
 */
private val STYLE_DIRECTIVE: Regex = Regex("""#\[[^\]]*]""")

/**
 * Removes every `#[...]` style directive from text tmux expanded as a format (for example
 * `#{T:window-status-format}`), leaving only the characters tmux would draw. An unterminated
 * `#[` is kept verbatim.
 */
fun stripStyleDirectives(text: String): String = STYLE_DIRECTIVE.replace(text, "")
