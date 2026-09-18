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
import androidx.compose.material3.Card
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
import dev.estaab.salchang.meta.Formatters
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

/** The Info tab for one window: parsed `@salchang_meta` of its active pane, or a hint. */
@Composable
fun InfoTab(window: TmuxWindow, parseMeta: (String) -> WindowMeta, modifier: Modifier = Modifier) {
    val raw: String? = window.activePaneId?.let { window.meta[it] } ?: window.meta.values.firstOrNull()
    val meta: WindowMeta? = raw?.let(parseMeta)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
    ) {
        when (meta) {
            null -> NoMetaHint()
            is WindowMeta.ClaudeCode -> ClaudeCodeInfo(meta)
            is WindowMeta.Generic -> {
                InfoCard("Metadata" + (meta.kind?.let { " ($it)" } ?: "")) {
                    if (meta.updatedAt != null) UpdatedAgo(meta.updatedAt)
                    Text(WindowMeta.pretty(meta.raw), style = MonoTextStyle)
                }
            }
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
private fun NoMetaHint() {
    InfoCard("No metadata") {
        Text("This window's pane has no @salchang_meta option.")
        Text(
            "Install remote/salchang-statusline as the Claude Code status line to publish model, " +
                "context and cost here, or set it by hand:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("tmux set-option -p @salchang_meta '{\"kind\":\"note\",\"data\":{}}'", style = MonoTextStyle)
    }
}

@Composable
private fun ClaudeCodeInfo(meta: WindowMeta.ClaudeCode) {
    val status: ClaudeStatus = meta.data
    InfoCard("Claude Code") {
        Row(horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING), verticalAlignment = Alignment.CenterVertically) {
            status.model?.let { model ->
                AssistChip(onClick = {}, label = { Text(model.displayName ?: model.id ?: "model") })
            }
            status.effort?.level?.let { AssistChip(onClick = {}, label = { Text("effort: $it") }) }
            if (status.thinking?.enabled == true) AssistChip(onClick = {}, label = { Text("thinking") })
            if (status.fastMode == true) AssistChip(onClick = {}, label = { Text("fast") })
        }
        status.version?.let { KeyValue("Version", it) }
        status.sessionName?.let { KeyValue("Session", it) }
        status.agent?.name?.let { KeyValue("Agent", it) }
        status.vim?.mode?.let { KeyValue("Vim mode", it) }
        if (meta.updatedAt != null) UpdatedAgo(meta.updatedAt)
    }

    val cwd: String? = status.cwd ?: status.workspace?.currentDir
    if (cwd != null || status.workspace != null || status.worktree != null) {
        InfoCard("Directory") {
            cwd?.let { Text(it, style = MonoTextStyle) }
            status.workspace?.projectDir?.let { KeyValue("Project", it) }
            status.workspace?.repo?.let { repo ->
                val name: String = listOfNotNull(repo.owner, repo.name).joinToString("/")
                if (name.isNotEmpty()) KeyValue("Repo", name)
            }
            status.worktree?.let { wt ->
                wt.name?.let { KeyValue("Worktree", it) }
                wt.branch?.let { KeyValue("Branch", it) }
            }
        }
    }

    status.contextWindow?.let { ContextWindowCard(it, status.exceeds200kTokens == true) }
    status.cost?.let { CostCard(it) }
    status.rateLimits?.let { RateLimitsCard(it) }
    status.pr?.let { PullRequestCard(it) }

    RawJsonCard(meta.raw)
}

@Composable
private fun ContextWindowCard(ctx: ClaudeContextWindow, exceeds200k: Boolean) {
    InfoCard("Context window") {
        val used: Double? = ctx.usedPercentage
        if (used != null) {
            LinearProgressIndicator(
                progress = { (used / PERCENT_MAX).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            KeyValue("Used", Formatters.formatPercent(used))
        }
        ctx.remainingPercentage?.let { KeyValue("Remaining", Formatters.formatPercent(it)) }
        ctx.contextWindowSize?.let { KeyValue("Size", Formatters.formatCount(it) + " tokens") }
        ctx.totalInputTokens?.let { KeyValue("Input tokens", Formatters.formatCount(it)) }
        ctx.totalOutputTokens?.let { KeyValue("Output tokens", Formatters.formatCount(it)) }
        ctx.currentUsage?.let { usage ->
            usage.cacheReadInputTokens?.let { KeyValue("Cache read", Formatters.formatCount(it)) }
            usage.cacheCreationInputTokens?.let { KeyValue("Cache created", Formatters.formatCount(it)) }
        }
        if (exceeds200k) Text("Exceeds 200k tokens", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CostCard(cost: ClaudeCost) {
    InfoCard("Cost") {
        cost.totalCostUsd?.let { KeyValue("Total", Formatters.formatUsd(it)) }
        cost.totalDurationMs?.let { KeyValue("Wall time", Formatters.formatDurationMs(it)) }
        cost.totalApiDurationMs?.let { KeyValue("API time", Formatters.formatDurationMs(it)) }
        val added: Long? = cost.totalLinesAdded
        val removed: Long? = cost.totalLinesRemoved
        if (added != null || removed != null) {
            KeyValue("Lines", "+${Formatters.formatCount(added ?: 0L)} / -${Formatters.formatCount(removed ?: 0L)}")
        }
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

/** "updated N ago" that re-renders every second. */
@Composable
private fun UpdatedAgo(updatedAtEpochSeconds: Long) {
    val now: Long by tickingNow()
    Text(
        "updated " + Formatters.formatTimeAgo(updatedAtEpochSeconds, now),
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
