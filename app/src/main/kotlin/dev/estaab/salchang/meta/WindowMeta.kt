package dev.estaab.salchang.meta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** `kind` of a Claude Code session, whether written by the probe or by `remote/salchang-statusline`. */
const val META_KIND_CLAUDE_CODE: String = "claude-code"

/** `source` written by `remote/salchang-probe`. */
const val META_SOURCE_PROBE: String = "probe"

/** `source` written by `remote/salchang-statusline` (schema v2); the old hook wrote no `source`. */
const val META_SOURCE_STATUSLINE: String = "statusline"

/** `status` values a producer may report; anything else is shown verbatim. */
const val META_STATUS_BUSY: String = "busy"
const val META_STATUS_IDLE: String = "idle"

private const val KEY_KIND: String = "kind"
private const val KEY_SOURCE: String = "source"
private const val KEY_UPDATED_AT: String = "updated_at"
private const val KEY_AGENT_NAME: String = "agent_name"
private const val KEY_VERSION: String = "version"
private const val KEY_CWD: String = "cwd"
private const val KEY_SESSION_ID: String = "session_id"
private const val KEY_TITLE: String = "title"
private const val KEY_STATUS: String = "status"
private const val KEY_STARTED_AT: String = "started_at"
private const val KEY_MODEL: String = "model"
private const val KEY_MODEL_NAME: String = "model_name"
private const val KEY_CONTEXT_USED: String = "context_used"
private const val KEY_CONTEXT_WINDOW: String = "context_window"
private const val KEY_COST_USD: String = "cost_usd"
private const val KEY_DATA: String = "data"

/**
 * Parsed value of the `@salchang_meta` pane option (schema v2, see scratch/probe-spec.md and
 * docs/DESIGN.md). One JSON object per pane with a set of common keys that every producer
 * (`remote/salchang-probe`, `remote/salchang-statusline`, or a hand-written option) may fill,
 * plus a free-form `data` object. Every key but `kind` is optional; missing and `null` are
 * equivalent.
 */
sealed interface WindowMeta {
    /**
     * A JSON object. Common fields are null when absent. [claude] is decoded from [data] only
     * for `kind == claude-code` payloads whose `source` is `statusline` or absent (the old hook
     * wrote no `source`), because only then is `data` the Claude Code status-line JSON.
     */
    data class Payload(
        /** Harness id (`claude-code`, `codex`, `pi`, ...); null when the producer omitted it. */
        val kind: String?,
        val source: String?,
        /** Epoch seconds. */
        val updatedAt: Long?,
        val agentName: String?,
        val version: String?,
        val cwd: String?,
        val sessionId: String?,
        val title: String?,
        /** [META_STATUS_BUSY], [META_STATUS_IDLE], or whatever the producer wrote. */
        val status: String?,
        /** Epoch seconds. */
        val startedAt: Long?,
        val model: String?,
        val modelName: String?,
        val contextUsed: Long?,
        val contextWindow: Long?,
        val costUsd: Double?,
        val data: JsonObject?,
        val claude: ClaudeStatus?,
        val raw: JsonObject,
    ) : WindowMeta

    /** The option value was not a JSON object. */
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

        private const val NOT_AN_OBJECT_ERROR: String = "expected a JSON object"

        fun parse(raw: String): WindowMeta {
            val element: JsonElement = try {
                json.parseToJsonElement(raw)
            } catch (e: SerializationException) {
                return Invalid(raw, e.message ?: "invalid JSON")
            } catch (e: IllegalArgumentException) {
                return Invalid(raw, e.message ?: "invalid JSON")
            }
            val obj: JsonObject = element as? JsonObject ?: return Invalid(raw, NOT_AN_OBJECT_ERROR)
            val kind: String? = obj.string(KEY_KIND)
            val source: String? = obj.string(KEY_SOURCE)
            val data: JsonObject? = obj[KEY_DATA] as? JsonObject
            val claude: ClaudeStatus? =
                if (kind == META_KIND_CLAUDE_CODE && data != null && (source == null || source == META_SOURCE_STATUSLINE)) {
                    decodeClaudeStatus(data)
                } else {
                    null
                }
            return Payload(
                kind = kind,
                source = source,
                updatedAt = obj.epochSeconds(KEY_UPDATED_AT),
                agentName = obj.string(KEY_AGENT_NAME),
                version = obj.string(KEY_VERSION),
                cwd = obj.string(KEY_CWD),
                sessionId = obj.string(KEY_SESSION_ID),
                title = obj.string(KEY_TITLE),
                status = obj.string(KEY_STATUS),
                startedAt = obj.epochSeconds(KEY_STARTED_AT),
                model = obj.string(KEY_MODEL),
                modelName = obj.string(KEY_MODEL_NAME),
                contextUsed = obj.integer(KEY_CONTEXT_USED),
                contextWindow = obj.integer(KEY_CONTEXT_WINDOW),
                costUsd = obj.number(KEY_COST_USD),
                data = data,
                claude = claude,
                raw = obj,
            )
        }

        /** Pretty-printed JSON for the raw sections of the Info tab. */
        fun pretty(element: JsonElement): String = prettyJson.encodeToString(JsonElement.serializer(), element)

        private fun decodeClaudeStatus(data: JsonObject): ClaudeStatus? = try {
            json.decodeFromJsonElement(ClaudeStatus.serializer(), data)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        /** The primitive at [key], or null when absent, `null`, or not a primitive. */
        private fun JsonObject.primitive(key: String): JsonPrimitive? {
            val value: JsonElement = this[key] ?: return null
            if (value is JsonNull) return null
            return value as? JsonPrimitive
        }

        /** A JSON string at [key]; numbers and booleans do not count. */
        private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.contentOrNull

        /** An integer at [key]; a float is truncated (producers may write `1.7e9`). Strings do not count. */
        private fun JsonObject.integer(key: String): Long? {
            val p: JsonPrimitive = primitive(key)?.takeIf { !it.isString } ?: return null
            return p.longOrNull ?: p.doubleOrNull?.toLong()
        }

        private fun JsonObject.epochSeconds(key: String): Long? = integer(key)

        private fun JsonObject.number(key: String): Double? = primitive(key)?.takeIf { !it.isString }?.doubleOrNull
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
