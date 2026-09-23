package dev.estaab.salchang.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ChipColors
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.estaab.salchang.meta.ClaudeContextWindow
import dev.estaab.salchang.meta.ClaudeCost
import dev.estaab.salchang.meta.ClaudePullRequest
import dev.estaab.salchang.meta.ClaudeRateLimit
import dev.estaab.salchang.meta.ClaudeRateLimits
import dev.estaab.salchang.meta.ClaudeStatus
import dev.estaab.salchang.meta.ClaudeWorkspace
import dev.estaab.salchang.meta.ClaudeWorktree
import dev.estaab.salchang.meta.Formatters
import dev.estaab.salchang.meta.META_STATUS_BUSY
import dev.estaab.salchang.meta.META_STATUS_IDLE
import dev.estaab.salchang.meta.WindowMeta
import dev.estaab.salchang.tmuxctl.TmuxWindow
import kotlinx.coroutines.delay

private val CARD_PADDING = 12.dp
private val SECTION_SPACING = 12.dp
private val ROW_SPACING = 4.dp
private val CHIP_SPACING = 6.dp

/** How often the "updated N ago" label is refreshed. */
private const val CLOCK_TICK_MS: Long = 1_000L

private const val PERCENT_MAX: Double = 100.0

/** Card title when the payload names neither an agent nor a kind. */
private const val FALLBACK_TITLE: String = "Metadata"

/** The example shown in the no-metadata hint. */
private const val SET_OPTION_EXAMPLE: String = "tmux set-option -p @salchang_meta '{\"kind\":\"note\"}'"

/**
 * The Info tab for one window: parsed `@salchang_meta` of its active pane, or a hint.
 * [probeError] is the agent probe's dependency error, if it reported one (see
 * `SessionController.probeError`).
 */
@Composable
fun InfoTab(window: TmuxWindow, parseMeta: (String) -> WindowMeta, probeError: String?, modifier: Modifier = Modifier) {
    val raw: String? = window.activePaneId?.let { window.meta[it] } ?: window.meta.values.firstOrNull()
    val meta: WindowMeta? = raw?.let(parseMeta)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
    ) {
        when (meta) {
            null -> NoMetaHint(probeError)
            is WindowMeta.Payload -> PayloadInfo(meta)
            is WindowMeta.Invalid -> {
                InfoCard("Invalid metadata") {
                    Text(meta.error, color = MaterialTheme.colorScheme.error)
                    Text(meta.raw, style = MonoTextStyle)
                }
            }
        }
    }
}

@Composable
private fun NoMetaHint(probeError: String?) {
    InfoCard("No metadata") {
        Text("No coding agent detected in this pane.")
        if (probeError != null) {
            Text("Agent detection is unavailable on this host:", style = MaterialTheme.typography.bodySmall)
            Text(probeError, style = MonoTextStyle, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "Claude Code users can additionally install remote/salchang-statusline as the status line " +
                "to publish cost and rate limits here. Any pane can also set the option by hand:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(SET_OPTION_EXAMPLE, style = MonoTextStyle)
    }
}

/**
 * Generic cards for every kind, then the Claude-specific cards when the payload came from the
 * status-line hook, then the raw JSON. For old-hook payloads the common keys are absent, so the
 * generic card falls back to the equivalent fields of the Claude status JSON; the Claude cards
 * then skip whatever the generic cards already show.
 */
@Composable
private fun PayloadInfo(meta: WindowMeta.Payload) {
    val claude: ClaudeStatus? = meta.claude
    val modelLabel: String? = meta.modelName ?: meta.model ?: claude?.model?.displayName ?: claude?.model?.id
    val version: String? = meta.version ?: claude?.version
    val cwd: String? = meta.cwd ?: claude?.cwd ?: claude?.workspace?.currentDir
    val title: String? = meta.title ?: claude?.sessionName

    AgentCard(meta, modelLabel = modelLabel, version = version, cwd = cwd, title = title)
    val claudeContext: ClaudeContextWindow? = claude?.contextWindow
    if (meta.contextUsed != null || claudeContext != null) {
        ContextCard(meta.contextUsed, meta.contextWindow, claudeContext, claude?.exceeds200kTokens == true)
    }
    val claudeCost: ClaudeCost? = claude?.cost
    if (meta.costUsd != null || claudeCost != null) CostCard(meta.costUsd, claudeCost)

    if (claude != null) {
        ClaudeDetailsCard(claude)
        ClaudeDirectoryCard(claude.workspace, claude.worktree)
        claude.rateLimits?.let { RateLimitsCard(it) }
        claude.pr?.let { PullRequestCard(it) }
    }

    RawJsonCard(meta.raw)
}

@Composable
private fun AgentCard(meta: WindowMeta.Payload, modelLabel: String?, version: String?, cwd: String?, title: String?) {
    InfoCard(meta.agentName ?: meta.kind ?: FALLBACK_TITLE) {
        if (modelLabel != null || meta.status != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING), verticalAlignment = Alignment.CenterVertically) {
                modelLabel?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                meta.status?.let { StatusChip(it) }
            }
        }
        title?.let { KeyValue("Title", it) }
        cwd?.let { Text(it, style = MonoTextStyle) }
        version?.let { KeyValue("Version", it) }
        if (meta.updatedAt != null || meta.startedAt != null) Timestamps(meta.updatedAt, meta.startedAt)
    }
}

/** `busy` and `idle` are colour-coded; any other status is shown verbatim in the default colours. */
@Composable
private fun StatusChip(status: String) {
    val scheme = MaterialTheme.colorScheme
    val colors: ChipColors = when (status) {
        META_STATUS_BUSY -> AssistChipDefaults.assistChipColors(containerColor = scheme.tertiaryContainer, labelColor = scheme.onTertiaryContainer)
        META_STATUS_IDLE -> AssistChipDefaults.assistChipColors(containerColor = scheme.secondaryContainer, labelColor = scheme.onSecondaryContainer)
        else -> AssistChipDefaults.assistChipColors()
    }
    AssistChip(onClick = {}, label = { Text(status) }, colors = colors)
}

/**
 * Context usage: a bar when both the common `context_used` and `context_window` are known,
 * tokens alone when only `context_used` is; otherwise the Claude hook's percentage. The Claude
 * detail rows that would repeat the summary are skipped.
 */
@Composable
private fun ContextCard(used: Long?, window: Long?, claude: ClaudeContextWindow?, exceeds200k: Boolean) {
    InfoCard("Context") {
        if (used != null && window != null && window > 0L) {
            val fraction: Double = used.toDouble() / window.toDouble()
            LinearProgressIndicator(progress = { fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            KeyValue("Used", "${Formatters.formatCount(used)} / ${Formatters.formatCount(window)} tokens (${Formatters.formatPercent(fraction * PERCENT_MAX)})")
        } else if (used != null) {
            KeyValue("Used", Formatters.formatCount(used) + " tokens")
            window?.let { KeyValue("Size", Formatters.formatCount(it) + " tokens") }
        } else if (claude != null) {
            claude.usedPercentage?.let { pct ->
                LinearProgressIndicator(progress = { (pct / PERCENT_MAX).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                KeyValue("Used", Formatters.formatPercent(pct))
            }
            claude.remainingPercentage?.let { KeyValue("Remaining", Formatters.formatPercent(it)) }
            claude.contextWindowSize?.let { KeyValue("Size", Formatters.formatCount(it) + " tokens") }
        }
        if (claude != null) {
            claude.totalInputTokens?.let { KeyValue("Input tokens", Formatters.formatCount(it)) }
            claude.totalOutputTokens?.let { KeyValue("Output tokens", Formatters.formatCount(it)) }
            claude.currentUsage?.let { usage ->
                usage.cacheReadInputTokens?.let { KeyValue("Cache read", Formatters.formatCount(it)) }
                usage.cacheCreationInputTokens?.let { KeyValue("Cache created", Formatters.formatCount(it)) }
            }
        }
        if (exceeds200k) Text("Exceeds 200k tokens", color = MaterialTheme.colorScheme.error)
    }
}

/** Session cost: the common `cost_usd` (or the Claude hook's total), plus the hook's timing and line counts. */
@Composable
private fun CostCard(costUsd: Double?, claude: ClaudeCost?) {
    InfoCard("Cost") {
        (costUsd ?: claude?.totalCostUsd)?.let { KeyValue("Total", Formatters.formatUsd(it)) }
        if (claude != null) {
            claude.totalDurationMs?.let { KeyValue("Wall time", Formatters.formatDurationMs(it)) }
            claude.totalApiDurationMs?.let { KeyValue("API time", Formatters.formatDurationMs(it)) }
            val added: Long? = claude.totalLinesAdded
            val removed: Long? = claude.totalLinesRemoved
            if (added != null || removed != null) {
                KeyValue("Lines", "+${Formatters.formatCount(added ?: 0L)} / -${Formatters.formatCount(removed ?: 0L)}")
            }
        }
    }
}

/** Claude Code settings the generic card has no slot for; omitted when none are set. */
@Composable
private fun ClaudeDetailsCard(status: ClaudeStatus) {
    val effort: String? = status.effort?.level
    val thinking: Boolean = status.thinking?.enabled == true
    val fast: Boolean = status.fastMode == true
    val agent: String? = status.agent?.name
    val vim: String? = status.vim?.mode
    if (effort == null && !thinking && !fast && agent == null && vim == null) return
    InfoCard("Claude Code") {
        if (effort != null || thinking || fast) {
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING), verticalAlignment = Alignment.CenterVertically) {
                effort?.let { AssistChip(onClick = {}, label = { Text("effort: $it") }) }
                if (thinking) AssistChip(onClick = {}, label = { Text("thinking") })
                if (fast) AssistChip(onClick = {}, label = { Text("fast") })
            }
        }
        agent?.let { KeyValue("Agent", it) }
        vim?.let { KeyValue("Vim mode", it) }
    }
}

/** Project, repo and worktree from the Claude hook; the cwd itself is in the generic card. */
@Composable
private fun ClaudeDirectoryCard(workspace: ClaudeWorkspace?, worktree: ClaudeWorktree?) {
    val projectDir: String? = workspace?.projectDir
    val repoName: String? = workspace?.repo?.let { repo -> listOfNotNull(repo.owner, repo.name).joinToString("/").ifEmpty { null } }
    if (projectDir == null && repoName == null && worktree == null) return
    InfoCard("Directory") {
        projectDir?.let { KeyValue("Project", it) }
        repoName?.let { KeyValue("Repo", it) }
        worktree?.name?.let { KeyValue("Worktree", it) }
        worktree?.branch?.let { KeyValue("Branch", it) }
    }
}

@Composable
private fun RateLimitsCard(limits: ClaudeRateLimits) {
    val now: Long by tickingNow()
    InfoCard("Rate limits") {
        RateLimitRow("5 hour", limits.fiveHour, now)
        RateLimitRow("7 day", limits.sevenDay, now)
        RateLimitRow("Spend", limits.spendLimit, now)
    }
}

@Composable
private fun RateLimitRow(label: String, limit: ClaudeRateLimit?, nowEpochSeconds: Long) {
    if (limit == null) return
    val used: Double? = limit.usedPercentage
    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING)) {
        val resets: String? = limit.resetsAt?.let { Formatters.formatResetsIn(it.toLong(), nowEpochSeconds) }
        KeyValue(label, listOfNotNull(used?.let(Formatters::formatPercent), resets).joinToString(", "))
        if (used != null) {
            LinearProgressIndicator(
                progress = { (used / PERCENT_MAX).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PullRequestCard(pr: ClaudePullRequest) {
    InfoCard("Pull request") {
        pr.number?.let { KeyValue("Number", "#$it") }
        pr.reviewState?.let { KeyValue("Review", it) }
        pr.url?.let { Text(it, style = MonoTextStyle) }
    }
}

@Composable
private fun RawJsonCard(raw: kotlinx.serialization.json.JsonElement) {
    var open: Boolean by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CARD_PADDING), verticalArrangement = Arrangement.spacedBy(ROW_SPACING)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("Raw JSON", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            if (open) Text(WindowMeta.pretty(raw), style = MonoTextStyle)
        }
    }
}

/** "updated N ago" and/or "started N ago", re-rendered every second. */
@Composable
private fun Timestamps(updatedAtEpochSeconds: Long?, startedAtEpochSeconds: Long?) {
    val now: Long by tickingNow()
    val parts: List<String> = listOfNotNull(
        updatedAtEpochSeconds?.let { "updated " + Formatters.formatTimeAgo(it, now) },
        startedAtEpochSeconds?.let { "started " + Formatters.formatTimeAgo(it, now) },
    )
    Text(
        parts.joinToString(", "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Current epoch seconds as state, refreshed every [CLOCK_TICK_MS] while composed. */
@Composable
private fun tickingNow(): androidx.compose.runtime.State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now.longValue = System.currentTimeMillis() / 1000L
        }
    }
    return now
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CARD_PADDING), verticalArrangement = Arrangement.spacedBy(ROW_SPACING)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
