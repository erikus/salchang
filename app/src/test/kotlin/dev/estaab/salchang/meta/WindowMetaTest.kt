package dev.estaab.salchang.meta

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payload as written by remote/salchang-probe for a Claude Code pane (schema v2). */
private const val PROBE_SAMPLE: String = """
{"kind":"claude-code","source":"probe","updated_at":1790115000,"agent_name":"Claude Code","version":"2.1.278",
 "cwd":"/home/estaab/code/salchang","session_id":"a1b261dc-1","title":"salchang-09","status":"busy",
 "started_at":1790093706,"model":"claude-fable-5-1","context_used":32931,"context_window":200000,
 "data":{"pid":1386682,"tmux":"S-7:@50.%50","transcript":"/home/estaab/.claude/projects/x/a1b261dc-1.jsonl"}}
"""

/** Realistic payload as written by remote/salchang-statusline from Claude Code's status line JSON. */
private const val CLAUDE_SAMPLE: String = """
{
  "kind": "claude-code",
  "updated_at": 1758100000,
  "data": {
    "hook_event_name": "Status",
    "session_id": "abc123",
    "transcript_path": "/home/u/.claude/projects/x/abc123.jsonl",
    "cwd": "/home/u/code/salchang",
    "model": { "id": "claude-opus-4-1", "display_name": "Opus" },
    "workspace": {
      "current_dir": "/home/u/code/salchang",
      "project_dir": "/home/u/code/salchang",
      "repo": { "host": "github.com", "owner": "estaab", "name": "salchang" }
    },
    "version": "2.0.1",
    "output_style": { "name": "default" },
    "cost": {
      "total_cost_usd": 1.2345,
      "total_duration_ms": 3725000,
      "total_api_duration_ms": 120000,
      "total_lines_added": 156,
      "total_lines_removed": 23
    },
    "context_window": {
      "context_window_size": 200000,
      "used_percentage": 42.5,
      "remaining_percentage": 57.5,
      "total_input_tokens": 80000,
      "total_output_tokens": 5000,
      "current_usage": {
        "input_tokens": 1000,
        "output_tokens": 200,
        "cache_creation_input_tokens": 3000,
        "cache_read_input_tokens": 76000
      }
    },
    "rate_limits": {
      "five_hour": { "used_percentage": 12, "resets_at": 1758110000 },
      "seven_day": { "used_percentage": 30.25, "resets_at": 1758500000.5 }
    },
    "effort": { "level": "high" },
    "thinking": { "enabled": true },
    "fast_mode": false,
    "exceeds_200k_tokens": false,
    "some_future_field": { "nested": [1, 2, 3] }
  }
}
"""

class WindowMetaTest {
    @Test
    fun oldHookEnvelopeDecodesClaudeStatus() {
        val meta: WindowMeta = WindowMeta.parse(CLAUDE_SAMPLE)
        assertTrue("expected Payload, got $meta", meta is WindowMeta.Payload)
        meta as WindowMeta.Payload
        assertEquals("claude-code", meta.kind)
        assertNull(meta.source)
        assertEquals(1758100000L, meta.updatedAt)
        // The old hook carries everything inside data; no common keys at the top level.
        assertNull(meta.model)
        assertNull(meta.cwd)
        assertNull(meta.contextUsed)
        val claude: ClaudeStatus = requireNotNull(meta.claude) { "old hook envelope must decode ClaudeStatus" }
        assertEquals("Opus", claude.model?.displayName)
        assertEquals("claude-opus-4-1", claude.model?.id)
        assertEquals("/home/u/code/salchang", claude.cwd)
        assertEquals("salchang", claude.workspace?.repo?.name)
        assertEquals(1.2345, claude.cost?.totalCostUsd!!, 1e-9)
        assertEquals(3725000L, claude.cost?.totalDurationMs)
        assertEquals(156L, claude.cost?.totalLinesAdded)
        assertEquals(200000L, claude.contextWindow?.contextWindowSize)
        assertEquals(42.5, claude.contextWindow?.usedPercentage!!, 1e-9)
        assertEquals(76000L, claude.contextWindow?.currentUsage?.cacheReadInputTokens)
        assertEquals(12.0, claude.rateLimits?.fiveHour?.usedPercentage!!, 1e-9)
        assertEquals(1758500000.5, claude.rateLimits?.sevenDay?.resetsAt!!, 1e-9)
        assertEquals("high", claude.effort?.level)
        assertEquals(true, claude.thinking?.enabled)
        assertEquals(false, claude.fastMode)
        assertNull(claude.pr)
        assertTrue(meta.data is JsonObject)
        assertTrue(WindowMeta.pretty(meta.raw).contains("\"kind\": \"claude-code\""))
    }

    @Test
    fun probePayloadYieldsCommonFieldsAndNoClaudeStatus() {
        val meta: WindowMeta = WindowMeta.parse(PROBE_SAMPLE)
        assertTrue("expected Payload, got $meta", meta is WindowMeta.Payload)
        meta as WindowMeta.Payload
        assertEquals("claude-code", meta.kind)
        assertEquals("probe", meta.source)
        assertEquals(1790115000L, meta.updatedAt)
        assertEquals("Claude Code", meta.agentName)
        assertEquals("2.1.278", meta.version)
        assertEquals("/home/estaab/code/salchang", meta.cwd)
        assertEquals("a1b261dc-1", meta.sessionId)
        assertEquals("salchang-09", meta.title)
        assertEquals("busy", meta.status)
        assertEquals(1790093706L, meta.startedAt)
        assertEquals("claude-fable-5-1", meta.model)
        assertNull(meta.modelName)
        assertEquals(32931L, meta.contextUsed)
        assertEquals(200000L, meta.contextWindow)
        assertNull(meta.costUsd)
        assertNotNull(meta.data)
        assertNull("probe data is not the status-line JSON", meta.claude)
    }

    @Test
    fun statuslineSourceDecodesClaudeStatusFromData() {
        val meta: WindowMeta.Payload = WindowMeta.parse(
            """{"kind":"claude-code","source":"statusline","model":"claude-opus-4-1","cost_usd":0.5,"data":{"model":{"display_name":"Opus"}}}""",
        ) as WindowMeta.Payload
        assertEquals("Opus", meta.claude?.model?.displayName)
        assertEquals("claude-opus-4-1", meta.model)
        assertEquals(0.5, meta.costUsd!!, 1e-9)
    }

    @Test
    fun claudeCodeWithMinimalData() {
        val meta: WindowMeta = WindowMeta.parse("""{"kind":"claude-code","data":{}}""")
        assertTrue(meta is WindowMeta.Payload)
        meta as WindowMeta.Payload
        assertNull(meta.updatedAt)
        assertNotNull(meta.claude)
    }

    @Test
    fun claudeKindWithoutDataHasNoClaudeStatus() {
        val meta: WindowMeta.Payload = WindowMeta.parse("""{"kind":"claude-code"}""") as WindowMeta.Payload
        assertNull(meta.data)
        assertNull(meta.claude)
    }

    @Test
    fun unknownKindKeepsRaw() {
        val meta: WindowMeta = WindowMeta.parse("""{"kind":"note","updated_at":1700000000,"data":{"text":"hi"}}""")
        assertTrue(meta is WindowMeta.Payload)
        meta as WindowMeta.Payload
        assertEquals("note", meta.kind)
        assertEquals(1700000000L, meta.updatedAt)
        assertNull(meta.agentName)
        assertNull(meta.claude)
        assertEquals("hi", (meta.data?.get("text") as? JsonPrimitive)?.content)
        assertTrue(WindowMeta.pretty(meta.raw).contains("\"text\": \"hi\""))
    }

    @Test
    fun objectWithoutKindIsPayloadWithNullKind() {
        val meta: WindowMeta = WindowMeta.parse("""{"foo": 1}""")
        assertTrue(meta is WindowMeta.Payload)
        assertNull((meta as WindowMeta.Payload).kind)
    }

    @Test
    fun nullAndWrongTypesReadAsAbsent() {
        val meta: WindowMeta.Payload = WindowMeta.parse(
            """{"kind":"codex","status":null,"context_used":"lots","context_window":1.5e5,"model":42,"cost_usd":"1"}""",
        ) as WindowMeta.Payload
        assertNull(meta.status)
        assertNull(meta.contextUsed)
        assertEquals(150000L, meta.contextWindow)
        assertNull(meta.model)
        assertNull(meta.costUsd)
    }

    @Test
    fun nonObjectJsonIsInvalid() {
        val meta: WindowMeta = WindowMeta.parse("[1, 2, 3]")
        assertTrue(meta is WindowMeta.Invalid)
        assertEquals("[1, 2, 3]", (meta as WindowMeta.Invalid).raw)
    }

    @Test
    fun malformedJsonIsInvalid() {
        val meta: WindowMeta = WindowMeta.parse("{not json")
        assertTrue(meta is WindowMeta.Invalid)
        meta as WindowMeta.Invalid
        assertEquals("{not json", meta.raw)
        assertNotNull(meta.error)
    }

    @Test
    fun emptyStringIsInvalid() {
        assertTrue(WindowMeta.parse("") is WindowMeta.Invalid)
    }
}
