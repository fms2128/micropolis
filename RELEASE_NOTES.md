# Release Notes — Micropolis AI Update

**Date:** April 1, 2026
**Commit:** `2887abf`
**Stats:** 455 files changed, 10,761 insertions, 91 deletions

---

## Overview

This release transforms the Micropolis AI assistant from a basic tool-calling agent into a self-improving city manager with persistent memory, strategic objectives, and deep knowledge of the game engine's internals. On the art side, a complete asset redesign pipeline was added to modernize the classic 16x16 pixel tiles.

---

## AI Agent — Self-Learning System

### Long-Term Memory (`agent_memory.md`)

The AI agent now has a persistent memory file that survives across games and sessions. It records what strategies work, what doesn't, placement rules discovered through trial and error, financial management tips, and a continuously evolving "best strategy" section. The agent reads this at session start and writes to it after observing positive or negative reward signals, creating a genuine learning loop.

### Game Strategy Guide (`game_strategy_guide.md`)

A comprehensive strategy reference was extracted directly from the engine source code — every formula, threshold, and causal relationship that governs the simulation. Covers:

- All buildable objects with exact costs, sizes, and maintenance
- The full simulation dependency graph (power → growth, pollution → land value → crime, etc.)
- Zone growth mechanics with the exact `zscore = globalValve + localScore` formula
- Demand valve calculations including the 21-value TaxTable
- The complete 6-step city score calculation with all multiplicative penalties
- Growth caps (Stadium at resPop > 500, Airport at comPop > 100, Seaport at indPop > 70)
- Strategic implications and derived optimal play patterns

The agent can access this via a new `read_strategy_guide` tool to reason about *why* the score changed rather than guessing.

### Short-Term Objectives System

A new objectives framework lets the agent set 1–5 measurable goals per game (e.g. "Power all zones", "Reach 500 population", "Lower pollution below 50"). Objectives persist across turns, appear in the agent's context each turn, and can be marked as completed. This keeps the agent focused and prevents aimless building.

- `set_objectives` — set/replace the active goal list
- `complete_objective` — mark a goal as done (moves to completed list)
- `get_objectives` — review active and completed goals

### Expanded Information Tools

Replaced the single `get_budget_details` tool with a suite of focused information tools:

| New Tool | What It Returns |
|----------|----------------|
| `get_overview` | Population, date, funds, score, city class, approval |
| `get_budget` | Tax rate, income, road/fire/police funding details |
| `get_evaluation` | Score trends, citizen problems with severity |
| `get_infrastructure` | Zone counts, powered/unpowered, roads, rails, stations |
| `get_demand` | R/C/I demand valves, caps, population per type |
| `get_averages` | Crime, pollution, land value, traffic averages |
| `get_map_overview` | Compressed 12x10 sector grid with development stats |

This lets the agent fetch only what it needs per turn, reducing token waste and improving response quality.

### Reward Signal & Learning Loop

The system prompt now explicitly teaches the agent the reward formula (`reward = delta_score * 2 + delta_population / 100 + delta_funds / 500`) and instructs it to:

1. Read memory at session start
2. Take actions and observe reward
3. Record positive/negative outcomes to the appropriate memory section
4. Consolidate proven knowledge into the "Current Best Strategy"

### Improved System Prompt

Complete rewrite of the system prompt with:

- Structured sections for game mechanics, placement rules, strategy, objectives, reward signal, strategy guide reference, memory system, and behavioral instructions
- Game event categorization ([CRITICAL], [DISASTER], [DEMAND], [PROBLEM], [FINANCIAL], [MILESTONE]) so the agent knows what to address first
- Clear coordinate semantics (top-left corner, exact building sizes)

### Placement Fix

Fixed a coordinate offset bug where the engine treated coordinates as center-point for buildings with size >= 3, while the AI passed top-left corner coordinates. Added +1 offset correction so `place_zone(x, y)` consistently means "top-left corner at (x, y)".

### Game Event Listener (`AIGameListener`)

New event listener that hooks into the engine's `CityListener` interface to detect:

- Critical events (power outages, budget crises, disasters)
- Census and evaluation cycles
- Demand changes
- City messages with location context

Events are queued and categorized for the AI's next turn context, so it reacts to what's actually happening rather than polling.

---

## GUI Additions

### Agent Memory Dialog

New window (menu: Windows → Agent Memory) displays the agent's `agent_memory.md` in a read-only viewer. Lets the user see what the AI has learned across sessions — strategies that work, strategies that don't, placement rules, and its current master plan.

### Objectives Dialog

New window (menu: Windows → Objectives) shows the AI's active short-term objectives and recently completed ones. Updates in real-time as the agent sets and completes goals. Color-coded active (yellow) vs. completed (green) sections.

### Localization

Added menu entries and dialog strings for Agent Memory and Objectives in all four language files: English, German, French, and Swedish.

---

## Asset Redesign Pipeline

### Pipeline Tool (`tools/asset_redesign_pipeline.py`)

A Python-based asset modernization pipeline that:

1. Extracts all game graphics (tiles, sprites, icons) into individual PNGs (`extracted_images/`)
2. Applies a "neo SimCity 3D" visual style: 4x upscaling, enhanced contrast/sharpness, subtle depth effects, and noise texturing
3. Validates output dimensions against the tile spec (`tiles.rc`) and sprite definitions
4. Generates preview sheets and JSON reports for QA

~600 lines of Python using Pillow for image processing. Reads tile definitions directly from the game's `tiles.rc` resource file to ensure dimensional correctness.

### Extracted Images (`extracted_images/`)

Full extraction of the original game assets:
- 26 graphic sprite sheets (terrain, zones, buildings, roads, rails, wires, traffic, animations)
- 44 UI icons (toolbar icons, graph icons, legend)
- 47 individual sprite frames (train, helicopter, airplane, ship, monster, tornado, explosion, bus)

### Redesigned Assets (`redesigned_assets/`)

Complete redesigned output set:
- `originals/` — copies of source images for diffing
- `output/` — processed versions ready for game integration
- `manifests/asset_manifest.json` — mapping of every processed file with checksums
- `reports/` — validation report (2163 lines of JSON) and preview sprite sheets

---

## Other Changes

- **AnthropicClient:** Minor adjustments to the LLM API integration
- **LLMProvider:** Interface update for expanded capabilities
- **GameStateObserver:** Added 69 lines of new observation methods for the expanded info tools (map sectors, demand, averages, evaluation, infrastructure)
- **Sample city file:** `nyc.cty` added for testing

---

## File Summary

| Category | Files | Lines Added |
|----------|-------|-------------|
| AI Agent Core | 7 Java files | ~750 |
| Game Strategy Guide | 1 markdown | ~400 |
| Agent Memory | 1 markdown | ~85 |
| GUI (Dialogs + Menu) | 3 Java files + 4 properties | ~300 |
| Asset Pipeline | 1 Python script | ~600 |
| Extracted Images | 117 PNGs | — |
| Redesigned Assets | 117 PNGs + manifests + reports | ~2,900 (JSON) |
