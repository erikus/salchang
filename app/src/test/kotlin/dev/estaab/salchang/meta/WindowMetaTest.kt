package dev.estaab.salchang.meta

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun parsesClaudeCodeSample() {
        val meta: WindowMeta = WindowMeta.parse(CLAUDE_SAMPLE)
        assertTrue("expected ClaudeCode, got $meta", meta is WindowMeta.ClaudeCode)
        meta as WindowMeta.ClaudeCode
        assertEquals(1758100000L, meta.updatedAt)
        assertEquals("Opus", meta.data.model?.displayName)
        assertEquals("claude-opus-4-1", meta.data.model?.id)
        assertEquals("/home/u/code/salchang", meta.data.cwd)
        assertEquals("salchang", meta.data.workspace?.repo?.name)
        assertEquals(1.2345, meta.data.cost?.totalCostUsd!!, 1e-9)
        assertEquals(3725000L, meta.data.cost?.totalDurationMs)
        assertEquals(156L, meta.data.cost?.totalLinesAdded)
        assertEquals(200000L, meta.data.contextWindow?.contextWindowSize)
        assertEquals(42.5, meta.data.contextWindow?.usedPercentage!!, 1e-9)
        assertEquals(76000L, meta.data.contextWindow?.currentUsage?.cacheReadInputTokens)
        assertEquals(12.0, meta.data.rateLimits?.fiveHour?.usedPercentage!!, 1e-9)
        assertEquals(1758500000.5, meta.data.rateLimits?.sevenDay?.resetsAt!!, 1e-9)
        assertEquals("high", meta.data.effort?.level)
        assertEquals(true, meta.data.thinking?.enabled)
        assertEquals(false, meta.data.fastMode)
        assertNull(meta.data.pr)
        assertTrue(meta.raw is JsonObject)
        assertTrue(WindowMeta.pretty(meta.raw).contains("\"kind\": \"claude-code\""))
    }

    @Test
    fun claudeCodeWithMinimalData() {
        val meta: WindowMeta = WindowMeta.parse("""{"kind":"claude-code","data":{}}""")
        assertTrue(meta is WindowMeta.ClaudeCode)
        assertNull((meta as WindowMeta.ClaudeCode).updatedAt)
    }

    @Test
    fun unknownKindIsGeneric() {
        val meta: WindowMeta = WindowMeta.parse("""{"kind":"note","updated_at":1700000000,"data":{"text":"hi"}}""")
        assertTrue(meta is WindowMeta.Generic)
        meta as WindowMeta.Generic
        assertEquals("note", meta.kind)
        assertEquals(1700000000L, meta.updatedAt)
    }

    @Test
    fun objectWithoutKindIsGeneric() {
        val meta: WindowMeta = WindowMeta.parse("""{"foo": 1}""")
        assertTrue(meta is WindowMeta.Generic)
        assertNull((meta as WindowMeta.Generic).kind)
    }

    @Test
    fun claudeKindWithMissingDataIsGeneric() {
        val meta: WindowMeta = WindowMeta.parse("""{"kind":"claude-code"}""")
        assertTrue("expected Generic, got $meta", meta is WindowMeta.Generic)
    }

    @Test
    fun nonObjectJsonIsGeneric() {
        val meta: WindowMeta = WindowMeta.parse("[1, 2, 3]")
        assertTrue(meta is WindowMeta.Generic)
        assertNull((meta as WindowMeta.Generic).kind)
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
