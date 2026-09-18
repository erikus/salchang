package dev.estaab.salchang.meta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** `kind` value written by `remote/salchang-statusline`. */
const val META_KIND_CLAUDE_CODE: String = "claude-code"

private const val KEY_KIND: String = "kind"
private const val KEY_UPDATED_AT: String = "updated_at"
private const val KEY_DATA: String = "data"

/**
 * Parsed value of the `@salchang_meta` pane option (see docs/DESIGN.md). The envelope is
 * `{"kind": <string>, "updated_at": <epoch seconds>, "data": {...}}`; only `claude-code` payloads
 * are understood in detail, everything else is kept as raw JSON for display.
 */
sealed interface WindowMeta {
    /** A Claude Code status-line payload. */
    data class ClaudeCode(
        val updatedAt: Long?,
        val data: ClaudeStatus,
        val raw: JsonObject,
    ) : WindowMeta

    /** Valid JSON with an unknown (or missing) kind, or a `claude-code` payload we could not decode. */
    data class Generic(
        val kind: String?,
        val updatedAt: Long?,
        val raw: JsonElement,
    ) : WindowMeta

    /** The option value was not JSON. */
    data class Invalid(
        val raw: String,
        val error: String,
    ) : WindowMeta

    companion object {
        private val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        private val prettyJson: Json = Json { prettyPrint = true }

        fun parse(raw: String): WindowMeta {
            val element: JsonElement = try {
                json.parseToJsonElement(raw)
            } catch (e: SerializationException) {
                return Invalid(raw, e.message ?: "invalid JSON")
            } catch (e: IllegalArgumentException) {
                return Invalid(raw, e.message ?: "invalid JSON")
            }
            val obj: JsonObject = element as? JsonObject ?: return Generic(kind = null, updatedAt = null, raw = element)
            val kind: String? = (obj[KEY_KIND] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val updatedAt: Long? = (obj[KEY_UPDATED_AT] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
            val data: JsonObject? = obj[KEY_DATA] as? JsonObject
            if (kind == META_KIND_CLAUDE_CODE && data != null) {
                val status: ClaudeStatus? = try {
                    json.decodeFromJsonElement(ClaudeStatus.serializer(), data)
                } catch (e: SerializationException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (status != null) return ClaudeCode(updatedAt = updatedAt, data = status, raw = obj)
            }
            return Generic(kind = kind, updatedAt = updatedAt, raw = obj)
        }

        /** Pretty-printed JSON for the raw sections of the Info tab. */
        fun pretty(element: JsonElement): String = prettyJson.encodeToString(JsonElement.serializer(), element)
    }
}

/**
 * The JSON Claude Code passes to `statusLine` commands on stdin. Every field is optional because
 * the payload varies between versions and between hook contexts.
 */
@Serializable
data class ClaudeStatus(
    val model: ClaudeModel? = null,
    val cwd: String? = null,
    val workspace: ClaudeWorkspace? = null,
    val version: String? = null,
    @SerialName("session_name") val sessionName: String? = null,
    val cost: ClaudeCost? = null,
    @SerialName("context_window") val contextWindow: ClaudeContextWindow? = null,
    @SerialName("rate_limits") val rateLimits: ClaudeRateLimits? = null,
    val effort: ClaudeEffort? = null,
    val thinking: ClaudeThinking? = null,
    @SerialName("fast_mode") val fastMode: Boolean? = null,
    val agent: ClaudeAgent? = null,
    val pr: ClaudePullRequest? = null,
    val worktree: ClaudeWorktree? = null,
    val vim: ClaudeVim? = null,
    @SerialName("exceeds_200k_tokens") val exceeds200kTokens: Boolean? = null,
)

@Serializable
data class ClaudeModel(
    val id: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class ClaudeWorkspace(
    @SerialName("current_dir") val currentDir: String? = null,
    @SerialName("project_dir") val projectDir: String? = null,
    @SerialName("git_worktree") val gitWorktree: String? = null,
    val repo: ClaudeRepo? = null,
)

@Serializable
data class ClaudeRepo(
    val host: String? = null,
    val owner: String? = null,
    val name: String? = null,
)

@Serializable
data class ClaudeCost(
    @SerialName("total_cost_usd") val totalCostUsd: Double? = null,
    @SerialName("total_duration_ms") val totalDurationMs: Long? = null,
    @SerialName("total_api_duration_ms") val totalApiDurationMs: Long? = null,
    @SerialName("total_lines_added") val totalLinesAdded: Long? = null,
    @SerialName("total_lines_removed") val totalLinesRemoved: Long? = null,
)

@Serializable
data class ClaudeContextWindow(
    @SerialName("context_window_size") val contextWindowSize: Long? = null,
    @SerialName("used_percentage") val usedPercentage: Double? = null,
    @SerialName("remaining_percentage") val remainingPercentage: Double? = null,
    @SerialName("total_input_tokens") val totalInputTokens: Long? = null,
    @SerialName("total_output_tokens") val totalOutputTokens: Long? = null,
    @SerialName("current_usage") val currentUsage: ClaudeCurrentUsage? = null,
)

@Serializable
data class ClaudeCurrentUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long? = null,
)

@Serializable
data class ClaudeRateLimits(
    @SerialName("five_hour") val fiveHour: ClaudeRateLimit? = null,
    @SerialName("seven_day") val sevenDay: ClaudeRateLimit? = null,
    @SerialName("spend_limit") val spendLimit: ClaudeRateLimit? = null,
)

@Serializable
data class ClaudeRateLimit(
    @SerialName("used_percentage") val usedPercentage: Double? = null,
    /** Epoch seconds. */
    @SerialName("resets_at") val resetsAt: Double? = null,
)

@Serializable
data class ClaudeEffort(val level: String? = null)

@Serializable
data class ClaudeThinking(val enabled: Boolean? = null)

@Serializable
data class ClaudeAgent(val name: String? = null)

@Serializable
data class ClaudePullRequest(
    val number: Long? = null,
    val url: String? = null,
    @SerialName("review_state") val reviewState: String? = null,
)

@Serializable
data class ClaudeWorktree(
    val name: String? = null,
    val branch: String? = null,
)

@Serializable
data class ClaudeVim(val mode: String? = null)
