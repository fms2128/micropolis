# Release Notes — Micropolis AI Update v3

**Date:** April 2, 2026
**Previous:** `f8fc863` (Refactor AI agent: extract LLMClient, simplify system prompt, streamline tools)
**Stats:** ~1,700 lines added across 17 modified files + 2 new Java classes

---

## Overview

This release significantly upgrades the AI agent's strategic capabilities and observability. The agent now has automatic power grid repair, deep demand analysis, a structured strategic planning loop, full event bus logging with a new Activity Log GUI tab, and map screenshot support for vision-capable LLMs.

---

## New AI Tools

### `connect_power(x, y)` — Automatic Power Grid Repair

BFS-based pathfinding from any unpowered zone to the nearest powered tile. Automatically lays wire along the discovered path, handling road crossings (wire+road tiles) transparently. Replaces manual power line building for repair scenarios.

### `analyze_demand` — Deep Demand Analysis

Replicates the engine's internal valve formula and returns a detailed breakdown per zone type (R/C/I): current valve value, employment ratio, migration pressure, labor base, projected vs actual population, tax stimulus effect, growth cap status with distance-to-cap, trend direction, and actionable recommendations.

### `strategic_plan` — AI Planning Engine

The agent's new primary planning tool. Analyzes the current game state against the strategy guide and generates a concrete, prioritized action plan with:
- Game phase assessment (early/growth/mature)
- Priority analysis based on engine mechanics
- Recommended objectives with exact tools to use
- Cost estimates and verification criteria for each objective

### `capture_map_screenshot` — Vision Support

Renders the full 120×100 tile map as a 960×800 PNG image (8px tiles), base64-encoded for sending to vision-capable LLMs like Claude. Gives the AI a visual overview of the entire city.

---

## Enhanced Strategic Planning Loop

The system prompt now enforces a disciplined planning cycle:

1. **PLAN:** Call `strategic_plan` → get prioritized objectives with tools and verification criteria
2. **SET:** Call `set_objectives` with the suggested objectives
3. **EXECUTE:** Work through each objective
4. **VERIFY:** Run the verification check before completing
5. **COMPLETE:** Call `complete_objective(index, verification_evidence)`
6. **REPEAT:** When all objectives done → `strategic_plan` again

Objectives now require verification evidence when marking as complete, preventing premature completion.

---

## Activity Logger & Event Bus

### `ActivityLogger` (new class)

Structured JSONL logger that records every turn, tool call, reward signal, and game event to `ai_data/activity_log.jsonl`. Each line is a typed JSON object (`turn_start`, `tool_call`, `event`, `turn_end`, `agent_thinking`) enabling post-game analysis and agent optimization.

Log files are automatically rotated per session.

### Enhanced `AIGameListener`

Transformed from a simple critical-event detector into a full event bus. Now tracks and logs through ActivityLogger:
- All city messages with fine-grained categorization (DISASTER, CRITICAL, MILESTONE, DEMAND, FINANCIAL, PROBLEM, INFO)
- Fund changes with debounced logging
- Demand valve shifts (R/C/I)
- Evaluation score changes
- Census events
- Sound events

---

## Improved `plan_city_block`

City blocks now build **perimeter roads on all 4 sides** — creating proper city blocks bounded by roads like a real city grid:

```
       ====== ROAD (top) ======
  ROAD [Zone strip][Zone strip] ROAD
  ROAD [Zone strip][Zone strip] ROAD
       ====== ROAD (internal) ==
  ROAD [Zone strip][Zone strip] ROAD
  ROAD [Zone strip][Zone strip] ROAD
       ====== ROAD (bottom) ====
```

Previously, roads were only placed between zone strips (internal), leaving the block edges unconnected.

---

## GUI — Activity Log Tab

The AI Assistant panel now has two tabs:

- **Chat** — existing AI reasoning and action log
- **Activity Log** — color-coded real-time event feed from the ActivityLogger

Activity Log features:
- Color coding per event type (red for disasters, orange for critical, yellow for milestones, blue for demand, purple for financial, green for rewards)
- Filter dropdown to show only specific event types
- Auto-refresh timer
- Event count display

---

## Engine API Additions

Two new public methods on `Micropolis`:
- `getTaxEffect()` — exposes the internal tax effect value for demand analysis
- `forcePowerScan()` — triggers an immediate power grid recalculation (used after `connect_power` lays new wires)

---

## Cleanup & Removals

- **Removed `agent_memory.md`** and **`ai_learnings.log`** — the agent now plays with a clean slate each session instead of accumulating potentially incorrect learnings
- **Removed `session_notes.md` content** — reset for fresh sessions
- **Renamed menu entry:** "Agent Memory" → "Strategy Guide" (now shows `game_strategy_guide.md`)
- **Memory/learning tools disabled:** `write_learning`, `read_learnings`, `consolidate_learnings`, `read_memory`, `update_memory` removed from tool definitions

---

## Localization

Updated all four language files (English, German, French, Swedish) with renamed menu entries and dialog strings for the Strategy Guide viewer.

---

## File Summary

| Category | Files | Key Changes |
|----------|-------|-------------|
| New: ActivityLogger | 1 Java | ~180 lines — structured JSONL event logging |
| New: MapScreenshot | 1 Java | ~60 lines — full-map PNG rendering for vision LLMs |
| AI Core (AIAssistant) | 1 Java | +130 lines — activity logging integration, pre-state capture |
| AI Event Bus (AIGameListener) | 1 Java | +200 lines — full event categorization and logging |
| AI Tools (AIToolHandler) | 1 Java | +740 lines — connect_power, analyze_demand, strategic_plan, improved plan_city_block |
| AI Observation (GameStateObserver) | 1 Java | +380 lines — BFS power routing, demand analysis |
| System Prompt | 1 Java | +130 lines — strategic planning loop, updated tool docs |
| GUI (AIAssistantPanel) | 1 Java | +410 lines — Activity Log tab with filtering and color coding |
| Engine (Micropolis) | 1 Java | +10 lines — getTaxEffect, forcePowerScan |
| GUI (AgentMemoryDialog) | 1 Java | +10 lines — reads strategy guide instead of agent_memory |
| Localization | 4 properties | Menu/dialog string updates |
| Data cleanup | 3 files | Removed agent_memory.md, ai_learnings.log, reset session_notes |
| Config | 1 .gitignore | Exclude activity log JSONL files |
