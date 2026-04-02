package micropolisj.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import micropolisj.engine.*;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Defines the tool-use schemas for the Claude API and executes
 * tool calls against the Micropolis engine.
 */
public class AIToolHandler {

    private static final String AI_DATA_DIR = "ai_data";
    private static final String STRATEGY_FILE = AI_DATA_DIR + "/game_strategy_guide.md";
    private static final String SESSION_FILE = AI_DATA_DIR + "/session_notes.md";

    private final Micropolis engine;
    private final GameStateObserver observer;
    private final AIAssistant assistant;

    public AIToolHandler(Micropolis engine, GameStateObserver observer, AIAssistant assistant) {
        this.engine = engine;
        this.observer = observer;
        this.assistant = assistant;
    }

    public JsonArray getToolDefinitions() {
        JsonArray tools = new JsonArray();
        tools.add(makeTool("place_zone",
            "Place a 3x3 residential, commercial, or industrial zone. Costs $100. The x,y is the top-left corner of the 3x3 area. Requires empty/bulldozable land.",
            param("type", "string", "Zone type: residential, commercial, or industrial"),
            param("x", "integer", "X coordinate (0-119, top-left of 3x3 zone)"),
            param("y", "integer", "Y coordinate (0-99, top-left of 3x3 zone)")
        ));
        tools.add(makeTool("place_building",
            "Place a special building. Types: police (3x3, $500), fire (3x3, $500), stadium (4x4, $5000), seaport (4x4, $3000, must be near water), powerplant (4x4, $3000), nuclear (4x4, $5000), airport (6x6, $10000). x,y is top-left corner.",
            param("type", "string", "Building type: police, fire, stadium, seaport, powerplant, nuclear, airport"),
            param("x", "integer", "X coordinate (top-left corner)"),
            param("y", "integer", "Y coordinate (top-left corner)")
        ));
        tools.add(makeTool("build_road",
            "Build a road from (x1,y1) to (x2,y2). Costs $10 per tile, $50 over water. Can build straight lines.",
            param("x1", "integer", "Start X"), param("y1", "integer", "Start Y"),
            param("x2", "integer", "End X"), param("y2", "integer", "End Y")
        ));
        tools.add(makeTool("build_rail",
            "Build a rail line from (x1,y1) to (x2,y2). Costs $20 per tile, $100 over water.",
            param("x1", "integer", "Start X"), param("y1", "integer", "Start Y"),
            param("x2", "integer", "End X"), param("y2", "integer", "End Y")
        ));
        tools.add(makeTool("build_power_line",
            "Build a power line from (x1,y1) to (x2,y2). Costs $5 per tile, $25 over water. Essential for connecting zones to power plants.",
            param("x1", "integer", "Start X"), param("y1", "integer", "Start Y"),
            param("x2", "integer", "End X"), param("y2", "integer", "End Y")
        ));
        tools.add(makeTool("bulldoze",
            "Bulldoze/clear a tile at (x,y). Costs $1. Use to remove rubble, trees, or unwanted structures before building.",
            param("x", "integer", "X coordinate"), param("y", "integer", "Y coordinate")
        ));
        tools.add(makeTool("place_park",
            "Place a park at (x,y). Costs $10. Parks improve land value and citizen happiness.",
            param("x", "integer", "X coordinate"), param("y", "integer", "Y coordinate")
        ));
        tools.add(makeTool("set_tax_rate",
            "Set the city tax rate (0-20). Higher taxes = more revenue but slower growth and citizen unhappiness. 7% is default.",
            param("rate", "integer", "Tax rate percentage (0-20)")
        ));
        tools.add(makeTool("set_budget",
            "Set budget allocation percentages for road maintenance, police, and fire departments. Each value 0-100.",
            param("road_pct", "integer", "Road maintenance funding % (0-100)"),
            param("police_pct", "integer", "Police funding % (0-100)"),
            param("fire_pct", "integer", "Fire department funding % (0-100)")
        ));
        tools.add(makeTool("set_speed",
            "Set simulation speed. Options: PAUSED, SLOW, NORMAL, FAST, SUPER_FAST",
            param("speed", "string", "Speed: PAUSED, SLOW, NORMAL, FAST, SUPER_FAST")
        ));
        tools.add(makeTool("query_zone",
            "Query detailed info about a zone at (x,y). Returns building type, density, land value, crime, pollution, growth rate.",
            param("x", "integer", "X coordinate"), param("y", "integer", "Y coordinate")
        ));
        tools.add(makeTool("inspect_area",
            "Inspect a rectangular area of the map centered on (x,y) with given radius. Returns detailed tile information.",
            param("x", "integer", "Center X"), param("y", "integer", "Center Y"),
            param("radius", "integer", "Radius in tiles (recommend 3-8)")
        ));
        tools.add(makeTool("get_overview",
            "Get city overview: population, date, funds, score, city class, approval rating, game speed, map dimensions."
        ));
        tools.add(makeTool("get_budget",
            "Get detailed budget: total funds, tax rate, tax income, road/fire/police funding requests vs actual funding."
        ));
        tools.add(makeTool("get_evaluation",
            "Get city evaluation: score, population trends, approval rating, assessed value, and top citizen problems with severity."
        ));
        tools.add(makeTool("get_infrastructure",
            "Get infrastructure counts: residential/commercial/industrial zones, powered vs unpowered, roads, rails, police/fire stations, hospitals, power plants, etc."
        ));
        tools.add(makeTool("get_demand",
            "Get demand valves for residential/commercial/industrial (-2000 to +2000), whether each is capped, and current population per type."
        ));
        tools.add(makeTool("get_averages",
            "Get city-wide averages: crime, pollution, land value, traffic, and effectiveness of road/police/fire services."
        ));
        tools.add(makeTool("get_map_overview",
            "Get compressed map overview: 12x10 sector grid showing dominant tile type, development %, empty %, and unpowered zones per sector. Good for finding where to build."
        ));
        tools.add(makeTool("get_history",
            "Get historical trends for city metrics. Returns sampled data points, min/max/avg, and trend direction (rising_fast, rising, stable, falling, falling_fast). Use to detect patterns like 'crime rising over last 5 years' or 'cashflow declining'. Financial history includes per-cycle funds, tax income, and expenses.",
            param("metric", "string", "Metric: residential, commercial, industrial, crime, pollution, money, financial, or all"),
            param("period", "string", "Time range: 'recent' (last ~10 years, ~6-month samples) or 'long_term' (120 years, ~10-year samples)")
        ));
        tools.add(makeTool("get_city_entities",
            "Get a COMPLETE catalog of every building, zone, and problem on the map. "
            + "Returns: buildings array (type, position, powered, road_access, size), "
            + "zone_summary (total/powered/road counts per R/C/I type), "
            + "unpowered_zones and no_road_zones arrays (only the problematic ones with coordinates), "
            + "infrastructure counts (road/power_line/rail tiles), "
            + "and active problems (fire/rubble/flood/radiation with coordinates). "
            + "This is your PRIMARY analysis tool — call it FIRST before making any build or repair decisions. "
            + "All power checks use zone CENTER tiles only (matching engine behavior), so data is 100% accurate."
        ));
        tools.add(makeTool("diagnose_infrastructure",
            "Scan the ENTIRE map for infrastructure problems: unpowered zones, buildings without power, "
            + "zones without road access. Returns a prioritized issue list with exact coordinates. "
            + "For a full city overview including all buildings and zone counts, prefer get_city_entities instead."
        ));
        tools.add(makeTool("connect_power",
            "Automatically connect an unpowered zone to the power grid. "
            + "Uses BFS to find the nearest powered tile, then builds a wire path connecting them. "
            + "Handles road crossings automatically (places wire+road crossing tiles). "
            + "Use this instead of manually building power lines — it finds the optimal path. "
            + "Call diagnose_infrastructure or get_city_entities first to find unpowered zone coordinates.",
            param("x", "integer", "X coordinate of the unpowered zone (or nearby tile)"),
            param("y", "integer", "Y coordinate of the unpowered zone (or nearby tile)")
        ));
        tools.add(makeTool("analyze_demand",
            "Detailed demand analysis that replicates the engine's valve formula. "
            + "Returns for each zone type (R/C/I): current valve, employment ratio, migration, "
            + "labor base, projected vs actual population, tax stimulus effect, growth cap status "
            + "and distance to cap, trend direction, and actionable recommendations. "
            + "Use this BEFORE deciding what to build — it tells you WHY demand is what it is."
        ));
        tools.add(makeToolWithOptional("render_map",
            "Render a visual ASCII map of an area. Returns 3 layered views: terrain, zones (uppercase=powered, lowercase=UNPOWERED), "
            + "and infrastructure (roads/power/buildings). Much easier to read than inspect_area. "
            + "Use this to understand spatial layout before building. Essential for diagnosing road and power problems.",
            new String[][] {
                param("x", "integer", "Center X coordinate"),
                param("y", "integer", "Center Y coordinate"),
            },
            new String[][] {
                param("radius", "integer", "Radius in tiles (default 15, max 25)")
            }
        ));
        tools.add(makeTool("find_empty_area",
            "Scan the entire map to find a contiguous rectangular area of empty (dirt) tiles. Returns the top-left corner of the best area found, preferring areas near existing roads. Use BEFORE placing buildings/zones to find valid locations. For zones use 3x3, for powerplant/stadium/seaport use 4x4, for airport use 6x6.",
            param("width", "integer", "Required width in tiles (e.g. 3 for zones, 4 for powerplant, 6 for airport)"),
            param("height", "integer", "Required height in tiles (e.g. 3 for zones, 4 for powerplant, 6 for airport)")
        ));
        tools.add(makeToolWithOptional("plan_city_block",
            "Plan and build a proper CITY BLOCK in one call. Creates zones with roads on ALL 4 sides "
            + "(like a real city block) plus internal roads between zone strips:\n"
            + "       ====== ROAD (top) ======\n"
            + "  ROAD [Zone strip][Zone strip] ROAD\n"
            + "  ROAD [Zone strip][Zone strip] ROAD\n"
            + "       ====== ROAD (internal) ==\n"
            + "  ROAD [Zone strip][Zone strip] ROAD\n"
            + "  ROAD [Zone strip][Zone strip] ROAD\n"
            + "       ====== ROAD (bottom) ====\n"
            + "Each zone is 3x3. Perimeter roads on all sides create proper network connectivity. "
            + "Power wires bridge each road row so power propagates through the block. "
            + "Needs 1-tile margin on each side for perimeter roads (x >= 1, y >= 1). "
            + "Use connect_power to wire the block to a power plant afterward. "
            + "Use find_empty_area(blockWidth+2, blockHeight+2) to find a valid spot.",
            new String[][] {
                param("zone_type", "string", "Zone type: residential, commercial, or industrial"),
                param("x", "integer", "Top-left X coordinate of the block"),
                param("y", "integer", "Top-left Y coordinate of the block"),
                param("cols", "integer", "Number of zones per row (1-13). Each zone is 3 tiles wide."),
            },
            new String[][] {
                param("rows", "integer", "Number of zone rows/strips (1-10, default 2). Roads are auto-placed between every pair."),
            }
        ));
        tools.add(makeTool("read_strategy_guide",
            "Read the full game strategy guide with detailed engine mechanics, formulas, zone growth rules, "
            + "scoring system, costs, and strategic tips. Contains the source-of-truth for all game mechanics."
        ));
        tools.add(makeTool("strategic_plan",
            "YOUR PRIMARY PLANNING TOOL. Analyzes current game state against the strategy guide and generates "
            + "a concrete action plan with specific objectives. Returns: game phase assessment, priority analysis "
            + "based on engine mechanics, recommended objectives with EXACT tools to use, cost estimates, and "
            + "verification criteria for each objective. "
            + "ALWAYS call this: (1) at game start, (2) after completing all objectives, (3) after major events/disasters, "
            + "(4) when reward trend is declining. Then use set_objectives with the suggested objectives."
        ));
        tools.add(makeToolWithOptional("set_objectives",
            "Set your current short-term objectives (1-5 goals). These appear in every turn's context to keep you focused. "
            + "When the situation changes, use this to set entirely new objectives. "
            + "IMPORTANT: Link each objective to the message IDs (e.g. m5, m7) it addresses via linked_messages. "
            + "This prevents duplicate objectives for already-handled messages. "
            + "Messages shown as [UNCOVERED] MUST be addressed by new objectives.",
            new String[][] {
                param("objectives", "string", "Semicolon-separated list of 1-5 short-term objectives. E.g. 'Power all zones; Reach 500 population; Build residential away from industry'")
            },
            new String[][] {
                param("linked_messages", "string",
                    "Semicolon-separated groups of message IDs for each objective (matching order). "
                    + "Each group is comma-separated. E.g. 'm5,m7;m3;m12,m14' means obj1 covers m5+m7, obj2 covers m3, obj3 covers m12+m14. "
                    + "Use empty string for objectives not linked to specific messages.")
            }
        ));
        tools.add(makeTool("complete_objective",
            "Mark an objective as completed ONLY after verifying it is truly achieved. "
            + "You MUST provide verification evidence explaining HOW you confirmed the objective is done. "
            + "The tool returns current game metrics so you can cross-check. "
            + "WRONG: completing 'Power all zones' without checking unpowered_zones count. "
            + "RIGHT: completing 'Power all zones' after get_city_entities shows unpowered_zones=0. "
            + "If verification is weak, the objective stays active.",
            param("index", "integer", "1-based index of the objective to mark as completed"),
            param("verification", "string",
                "How you verified this objective is complete. Must reference a specific check you performed "
                + "(e.g. 'get_city_entities shows 0 unpowered zones', 'render_map confirms road network at x,y', "
                + "'get_demand shows res_valve=+800 after building 6 residential zones').")
        ));
        tools.add(makeTool("get_objectives",
            "Read your current short-term objectives and recently completed ones."
        ));
        tools.add(makeTool("write_session_note",
            "Record a map-specific note for this game session only. Use for coordinates, turn-specific observations, "
            + "and tactical details that are NOT general knowledge. Session notes are deleted when a new game starts. "
            + "For general reusable knowledge, use update_memory or write_learning instead.",
            param("note", "string", "The session-specific observation, including coordinates and turn details as needed.")
        ));
        tools.add(makeTool("read_session_notes",
            "Read all session notes for the current game. These are map-specific tactical notes that reset with each new game."
        ));
        tools.add(makeTool("dismiss_budget",
            "Close the budget dialog window if it is currently open. The budget dialog pops up at the end of each fiscal year (when auto-budget is off) and pauses the game until dismissed. Call this after reviewing/adjusting budget settings to resume the game."
        ));
        tools.add(makeTool("capture_map_screenshot",
            "Capture a visual screenshot of the ENTIRE city map as a PNG image. "
            + "Returns a 960x800 pixel image showing all tiles, roads, zones, buildings, terrain, and water. "
            + "Use this to visually inspect your city layout, verify building placement, check road connectivity, "
            + "and get an overall sense of how the city looks. Much more intuitive than ASCII render_map. "
            + "Costs nothing and has no side effects."
        ));
        tools.add(makeTool("end_turn",
            "Signal how to proceed after this turn. Call this as the LAST tool in every auto-play turn. "
            + "'continue' = immediately start the next turn (use when you have more objectives to work on right now). "
            + "'wait' = wait ~10 seconds for the simulation to advance before the next turn (use when you've placed zones/buildings and want to see growth, collect taxes, or let the simulation settle). "
            + "If you don't call this tool, the default is 'wait'.",
            param("action", "string", "Either 'continue' (next turn immediately) or 'wait' (pause ~10s for simulation)")
        ));
        tools.add(makeToolWithOptional("search_engine_code",
            "Search the game engine Java source code for keywords. Use this to understand game mechanics by finding the actual implementation: "
            + "how crime spreads, how zone growth works, how demand valves are calculated, how scoring is computed, etc. "
            + "Returns matching code lines with surrounding context. Great for diagnosing WHY something is happening.",
            new String[][] { param("query", "string",
                "Keyword or phrase to search for in source code (e.g. 'crimeRamp', 'pollutionMem', 'demand', 'taxEffect', 'doZoneGrowth', 'score')") },
            new String[][] { param("scope", "string",
                "Search scope: 'engine' (game mechanics, default), 'gui' (UI code), 'ai' (AI code), or 'all'. Defaults to 'engine'.") }
        ));
        tools.add(makeToolWithOptional("read_engine_file",
            "Read a specific Java source file from the game engine. Deeply understand how a subsystem works by reading its code. "
            + "Can extract a specific method, read a line range, or show the full file with method listing for navigation. "
            + "Key classes: Micropolis (main engine), MapScanner (zone processing, overlays), CityEval (scoring), "
            + "TrafficGen (traffic), TileConstants (tile IDs), BuildingTool (placement), CityBudget (finances), Disaster (disasters).",
            new String[][] { param("class_name", "string",
                "Java class name without .java (e.g. 'Micropolis', 'MapScanner', 'CityEval', 'TrafficGen')") },
            new String[][] {
                param("method", "string",
                    "Extract a specific method by name (e.g. 'setValves', 'crimeScan'). Returns the full method body with javadoc."),
                param("start_line", "integer",
                    "Read from this line number (1-based). Combine with end_line for a range."),
                param("end_line", "integer",
                    "Read up to this line number. Defaults to start_line + 100 if only start_line is given.")
            }
        ));
        return tools;
    }

    private static final java.util.Set<String> TRACKED_ACTIONS = java.util.Set.of(
        "place_zone", "place_building", "build_road", "build_rail",
        "build_power_line", "bulldoze", "place_park", "set_tax_rate", "set_budget",
        "plan_city_block", "connect_power"
    );

    private String summarizeInput(String toolName, JsonObject input) {
        switch (toolName) {
            case "place_zone":
                return input.get("type").getAsString() + " " + input.get("x") + "," + input.get("y");
            case "place_building":
                return input.get("type").getAsString() + " " + input.get("x") + "," + input.get("y");
            case "build_road": case "build_rail": case "build_power_line":
                return input.get("x1") + "," + input.get("y1") + "->" + input.get("x2") + "," + input.get("y2");
            case "set_tax_rate":
                return input.get("rate") + "%";
            case "set_budget":
                return "r" + input.get("road_pct") + "/p" + input.get("police_pct") + "/f" + input.get("fire_pct");
            case "plan_city_block":
                return input.get("zone_type").getAsString() + " " + input.get("cols") + "x"
                    + (input.has("rows") ? input.get("rows") : "2") + " at " + input.get("x") + "," + input.get("y");
            case "connect_power":
                return input.get("x") + "," + input.get("y");
            default:
                return input.get("x") + "," + input.get("y");
        }
    }

    public JsonObject executeTool(String toolName, JsonObject input) {
        if (TRACKED_ACTIONS.contains(toolName)) {
            assistant.recordAction(toolName, summarizeInput(toolName, input));
        }

        int fundsBefore = engine.getBudget().getTotalFunds();
        int scoreBefore = engine.getEvaluation().getCityScore();
        int popBefore = engine.getCityPopulation();
        long startTime = System.currentTimeMillis();

        JsonObject result = executeToolInternal(toolName, input);

        long duration = System.currentTimeMillis() - startTime;
        int fundsAfter = engine.getBudget().getTotalFunds();

        ActivityLogger logger = assistant.getActivityLogger();
        if (logger != null && LOGGED_TOOLS.contains(toolName)) {
            logger.logToolCall(toolName, input, result, duration,
                fundsBefore, fundsAfter, scoreBefore, popBefore);
        }

        return result;
    }

    private static final java.util.Set<String> LOGGED_TOOLS = java.util.Set.of(
        "place_zone", "place_building", "build_road", "build_rail",
        "build_power_line", "bulldoze", "place_park", "set_tax_rate", "set_budget",
        "plan_city_block", "connect_power", "set_speed", "set_objectives", "complete_objective",
        "end_turn"
    );

    private JsonObject executeToolInternal(String toolName, JsonObject input) {
        JsonObject result = new JsonObject();
        try {
            switch (toolName) {
                case "place_zone": return executePlaceZone(input);
                case "place_building": return executePlaceBuilding(input);
                case "build_road": return executeBuildLine(input, MicropolisTool.ROADS);
                case "build_rail": return executeBuildLine(input, MicropolisTool.RAIL);
                case "build_power_line": return executeBuildLine(input, MicropolisTool.WIRE);
                case "bulldoze": return executeBulldoze(input);
                case "place_park": return executePlacePark(input);
                case "set_tax_rate": return executeSetTax(input);
                case "set_budget": return executeSetBudget(input);
                case "set_speed": return executeSetSpeed(input);
                case "query_zone": return executeQueryZone(input);
                case "inspect_area": return executeInspectArea(input);
                case "get_overview": return observer.getOverview();
                case "get_budget": return observer.getBudgetInfo();
                case "get_evaluation": return observer.getEvaluation();
                case "get_infrastructure": return observer.getInfrastructure();
                case "get_demand": return observer.getDemand();
                case "get_averages": return observer.getAverages();
                case "get_map_overview": return executeGetMapOverview();
                case "get_history": return executeGetHistory(input);
                case "get_city_entities": return observer.getCityEntities();
                case "diagnose_infrastructure": return observer.diagnoseInfrastructure();
                case "connect_power": return executeConnectPower(input);
                case "analyze_demand": return observer.analyzeDemand();
                case "render_map": return executeRenderMap(input);
                case "find_empty_area": return executeFindEmptyArea(input);
                case "plan_city_block": return executePlanCityBlock(input);
                case "read_strategy_guide": return executeReadStrategyGuide();
                case "strategic_plan": return executeStrategicPlan();
                case "set_objectives": return executeSetObjectives(input);
                case "complete_objective": return executeCompleteObjective(input);
                case "get_objectives": return executeGetObjectives();
                case "write_session_note": return executeWriteSessionNote(input);
                case "read_session_notes": return executeReadSessionNotes();
                case "capture_map_screenshot": return executeCaptureMapScreenshot();
                case "dismiss_budget": return executeDismissBudget();
                case "end_turn": return executeEndTurn(input);
                case "search_engine_code": return executeSearchEngineCode(input);
                case "read_engine_file": return executeReadEngineFile(input);
                default:
                    result.addProperty("error", "Unknown tool: " + toolName);
            }
        } catch (Exception e) {
            result.addProperty("error", e.getMessage());
        }
        return result;
    }

    private JsonObject executePlaceZone(JsonObject input) {
        String type = input.get("type").getAsString();
        int x = input.get("x").getAsInt();
        int y = input.get("y").getAsInt();

        MicropolisTool tool;
        switch (type.toLowerCase()) {
            case "residential": tool = MicropolisTool.RESIDENTIAL; break;
            case "commercial": tool = MicropolisTool.COMMERCIAL; break;
            case "industrial": tool = MicropolisTool.INDUSTRIAL; break;
            default: return errorResult("Invalid zone type: " + type);
        }
        return applyTool(tool, x, y);
    }

    private JsonObject executePlaceBuilding(JsonObject input) {
        String type = input.get("type").getAsString();
        int x = input.get("x").getAsInt();
        int y = input.get("y").getAsInt();

        MicropolisTool tool;
        switch (type.toLowerCase()) {
            case "police": tool = MicropolisTool.POLICE; break;
            case "fire": tool = MicropolisTool.FIRE; break;
            case "stadium": tool = MicropolisTool.STADIUM; break;
            case "seaport": tool = MicropolisTool.SEAPORT; break;
            case "powerplant": tool = MicropolisTool.POWERPLANT; break;
            case "nuclear": tool = MicropolisTool.NUCLEAR; break;
            case "airport": tool = MicropolisTool.AIRPORT; break;
            default: return errorResult("Invalid building type: " + type);
        }
        return applyTool(tool, x, y);
    }

    private JsonObject executeBuildLine(JsonObject input, MicropolisTool tool) {
        int x1 = input.get("x1").getAsInt();
        int y1 = input.get("y1").getAsInt();
        int x2 = input.get("x2").getAsInt();
        int y2 = input.get("y2").getAsInt();

        ToolStroke stroke = tool.beginStroke(engine, x1, y1);
        stroke.dragTo(x2, y2);
        ToolResult tr = stroke.apply();

        if (tool == MicropolisTool.WIRE) {
            fixWireGaps(x1, y1, x2, y2);
        }

        return toolResultToJson(tr, tool.name() + " from (" + x1 + "," + y1 + ") to (" + x2 + "," + y2 + ")");
    }

    private void fixWireGaps(int x1, int y1, int x2, int y2) {
        if (Math.abs(x2 - x1) >= Math.abs(y2 - y1)) {
            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            for (int x = minX; x <= maxX; x++) {
                observer.forceWireCrossing(x, y1);
            }
        } else {
            int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            for (int y = minY; y <= maxY; y++) {
                observer.forceWireCrossing(x1, y);
            }
        }
    }

    private JsonObject executeBulldoze(JsonObject input) {
        int x = input.get("x").getAsInt();
        int y = input.get("y").getAsInt();
        return applyTool(MicropolisTool.BULLDOZER, x, y);
    }

    private JsonObject executePlacePark(JsonObject input) {
        int x = input.get("x").getAsInt();
        int y = input.get("y").getAsInt();
        return applyTool(MicropolisTool.PARK, x, y);
    }

    private JsonObject executeSetTax(JsonObject input) {
        int rate = input.get("rate").getAsInt();
        if (rate < 0 || rate > 20) return errorResult("Tax rate must be 0-20");
        engine.setCityTax(rate);
        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("new_tax_rate", rate);
        return r;
    }

    private JsonObject executeSetBudget(JsonObject input) {
        int road = input.get("road_pct").getAsInt();
        int police = input.get("police_pct").getAsInt();
        int fire = input.get("fire_pct").getAsInt();
        engine.setRoadPercent(Math.max(0, Math.min(100, road)) / 100.0);
        engine.setPolicePercent(Math.max(0, Math.min(100, police)) / 100.0);
        engine.setFirePercent(Math.max(0, Math.min(100, fire)) / 100.0);
        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("road_pct", road);
        r.addProperty("police_pct", police);
        r.addProperty("fire_pct", fire);
        return r;
    }

    private JsonObject executeSetSpeed(JsonObject input) {
        String speedStr = input.get("speed").getAsString();
        try {
            Speed speed = Speed.valueOf(speedStr.toUpperCase());
            engine.setSpeed(speed);
            JsonObject r = new JsonObject();
            r.addProperty("success", true);
            r.addProperty("speed", speed.name());
            return r;
        } catch (IllegalArgumentException e) {
            return errorResult("Invalid speed: " + speedStr + ". Use: PAUSED, SLOW, NORMAL, FAST, SUPER_FAST");
        }
    }

    private JsonObject executeQueryZone(JsonObject input) {
        int x = input.get("x").getAsInt();
        int y = input.get("y").getAsInt();
        if (!engine.testBounds(x, y)) return errorResult("Out of bounds");

        ZoneStatus zs = engine.queryZoneStatus(x, y);
        JsonObject r = new JsonObject();
        r.addProperty("x", x);
        r.addProperty("y", y);
        r.addProperty("tile_id", (int) engine.getTile(x, y));
        r.addProperty("powered", engine.isTilePowered(x, y));
        if (zs != null) {
            String[] buildings = {"Clear", "Water", "Trees", "Rubble", "Flood", "Radioactive",
                "Fire", "Road", "Power", "Rail", "Residential", "Commercial",
                "Industrial", "Seaport", "Airport", "Coal Power", "Fire Dept",
                "Police Dept", "Stadium", "Nuclear Power", "Draw Bridge",
                "Radar Dish", "Fountain", "Industrial", "Steelmill", "Small Park",
                "Unused", "Unknown"};
            int bi = zs.getBuilding();
            r.addProperty("building", bi < buildings.length ? buildings[bi] : "Unknown");
            String[] densities = {"", "Low", "Medium", "High", "Very High"};
            r.addProperty("density", zs.getPopDensity() > 0 && zs.getPopDensity() < densities.length ? densities[zs.getPopDensity()] : "N/A");
            String[] landValues = {"", "", "", "", "", "Slum", "Lower Class", "Middle Class", "Upper Class"};
            r.addProperty("land_value", zs.getLandValue() < landValues.length ? landValues[zs.getLandValue()] : "N/A");
            String[] crimes = {"", "", "", "", "", "", "", "", "", "Safe", "Light", "Moderate", "Dangerous"};
            r.addProperty("crime", zs.getCrimeLevel() < crimes.length ? crimes[zs.getCrimeLevel()] : "N/A");
            String[] pollutions = {"", "", "", "", "", "", "", "", "", "", "", "", "", "None", "Moderate", "Heavy", "Very Heavy"};
            r.addProperty("pollution", zs.getPollution() < pollutions.length ? pollutions[zs.getPollution()] : "N/A");
            String[] growths = {"", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "Declining", "Stable", "Slow Growth", "Fast Growth"};
            r.addProperty("growth", zs.getGrowthRate() < growths.length ? growths[zs.getGrowthRate()] : "N/A");
        }
        return r;
    }

    private JsonObject executeInspectArea(JsonObject input) {
        int x = input.get("x").getAsInt();
        int y = input.get("y").getAsInt();
        int radius = input.get("radius").getAsInt();
        radius = Math.min(radius, 10);
        return observer.inspectArea(x, y, radius);
    }

    private JsonObject executeGetMapOverview() {
        JsonObject result = new JsonObject();
        result.add("sectors", observer.getMapSectors());
        return result;
    }

    private JsonObject executeGetHistory(JsonObject input) {
        String metric = input.get("metric").getAsString();
        String period = input.get("period").getAsString();
        return observer.getHistoryData(metric, period);
    }

    private JsonObject applyTool(MicropolisTool tool, int x, int y) {
        if (!engine.testBounds(x, y)) return errorResult("Coordinates (" + x + "," + y + ") out of bounds. Map is 120x100.");

        // The engine's getBounds() subtracts 1 from x,y for tools with size >= 3,
        // treating the passed coordinate as the center rather than top-left.
        // Offset by +1 so the AI's "top-left" semantics work correctly.
        int engineX = x;
        int engineY = y;
        if (tool.getSize() >= 3) {
            engineX = x + 1;
            engineY = y + 1;
        }

        ToolStroke stroke = tool.beginStroke(engine, engineX, engineY);
        stroke.dragTo(engineX, engineY);
        ToolResult tr = stroke.apply();

        JsonObject result = toolResultToJson(tr, tool.name() + " at (" + x + "," + y + ")");
        if (tr != ToolResult.SUCCESS) {
            result.addProperty("diagnosis", diagnosePlacementFailure(tool, x, y));
        }
        return result;
    }

    private String diagnosePlacementFailure(MicropolisTool tool, int x, int y) {
        int w = getToolWidth(tool);
        int h = getToolHeight(tool);
        StringBuilder sb = new StringBuilder();

        if (tool == MicropolisTool.BULLDOZER) {
            if (engine.testBounds(x, y)) {
                char tile = engine.getTile(x, y);
                if (tile == 0) {
                    return "Tile (" + x + "," + y + ") is already empty dirt. Nothing to bulldoze.";
                }
                return "Cannot bulldoze tile " + (int) tile + " (" + classifyTileSimple(tile) + ") at (" + x + "," + y + ").";
            }
            return "Coordinates out of bounds.";
        }

        int waterCount = 0, roadCount = 0, treeCount = 0, buildingCount = 0, powerLineCount = 0, oobCount = 0;
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int tx = x + dx, ty = y + dy;
                if (!engine.testBounds(tx, ty)) {
                    oobCount++;
                    continue;
                }
                char tile = engine.getTile(tx, ty);
                String type = classifyTileSimple(tile);
                if ("water".equals(type)) waterCount++;
                else if ("road".equals(type)) roadCount++;
                else if ("power_line".equals(type)) powerLineCount++;
                else if ("tree".equals(type)) treeCount++;
                else if (tile == 0) { /* empty - ok */ }
                else buildingCount++;
            }
        }

        if (oobCount > 0) sb.append(oobCount).append(" tiles out of map bounds (120x100). ");
        if (waterCount > 0) sb.append(waterCount).append(" water tiles blocking. Cannot build on water. ");
        if (roadCount > 0) sb.append(roadCount).append(" road tiles. Bulldoze first or pick another spot. ");
        if (powerLineCount > 0) sb.append(powerLineCount).append(" power line tiles blocking. ");
        if (treeCount > 0 && !engine.isAutoBulldoze()) sb.append(treeCount).append(" tree tiles. Enable auto-bulldoze or bulldoze manually first. ");
        if (buildingCount > 0) sb.append(buildingCount).append(" existing structure tiles. Bulldoze first or pick empty land. ");
        if (sb.length() == 0) {
            sb.append("Footprint ").append(w).append("x").append(h).append(" from top-left (").append(x).append(",").append(y).append(") looks clear but engine rejected it. ");
            sb.append("Possible causes: insufficient funds, overlapping with existing zone center, or edge tiles occupied. Use find_empty_area to locate valid land.");
        }

        return sb.toString().trim();
    }

    private int getToolWidth(MicropolisTool tool) {
        switch (tool) {
            case RESIDENTIAL: case COMMERCIAL: case INDUSTRIAL:
            case POLICE: case FIRE: return 3;
            case STADIUM: case SEAPORT: case POWERPLANT: case NUCLEAR: return 4;
            case AIRPORT: return 6;
            default: return 1;
        }
    }

    private int getToolHeight(MicropolisTool tool) {
        return getToolWidth(tool);
    }

    private String classifyTileSimple(char tile) {
        if (tile == 0) return "empty";
        if (tile >= 2 && tile <= 20) return "water";
        if (tile >= 21 && tile <= 43) return "tree";
        if (tile >= 44 && tile <= 47) return "rubble";
        if (tile >= 64 && tile < 208) return "road";
        if (tile >= 208 && tile <= 222) return "power_line";
        return "building";
    }

    private JsonObject toolResultToJson(ToolResult tr, String description) {
        JsonObject r = new JsonObject();
        r.addProperty("success", tr == ToolResult.SUCCESS);
        r.addProperty("result", tr.name());
        r.addProperty("action", description);
        r.addProperty("funds_remaining", engine.getBudget().getTotalFunds());
        return r;
    }

    private JsonObject executeRenderMap(JsonObject input) {
        int x = input.get("x").getAsInt();
        int y = input.get("y").getAsInt();
        int radius = input.has("radius") && !input.get("radius").isJsonNull()
            ? input.get("radius").getAsInt() : 15;
        if (!engine.testBounds(x, y)) return errorResult("Center coordinates out of bounds");
        return observer.renderMapArea(x, y, radius);
    }

    private JsonObject executeConnectPower(JsonObject input) {
        int x = input.get("x").getAsInt();
        int y = input.get("y").getAsInt();
        return observer.connectPower(x, y);
    }

    private JsonObject executeFindEmptyArea(JsonObject input) {
        int w = input.get("width").getAsInt();
        int h = input.get("height").getAsInt();
        return observer.findEmptyArea(w, h);
    }

    /**
     * Place an entire city block using the optimal strip layout:
     * zones are grouped in pairs of strips with a road row between each pair,
     * and power wires bridging each road so power propagates throughout.
     *
     * Layout for rows=4, cols=3:
     *   y+0  to y+2:  [Z][Z][Z]  strip 0
     *   y+3:          ==ROAD===  (serves strips 0 and 1)
     *   y+4  to y+6:  [Z][Z][Z]  strip 1
     *   y+7  to y+9:  [Z][Z][Z]  strip 2  (touches strip 1 → power flows)
     *   y+10:         ==ROAD===  (serves strips 2 and 3)
     *   y+11 to y+13: [Z][Z][Z]  strip 3
     */
    private JsonObject executePlanCityBlock(JsonObject input) {
        String zoneType = input.get("zone_type").getAsString().toLowerCase();
        int x = input.get("x").getAsInt();
        int y = input.get("y").getAsInt();
        int cols = Math.max(1, Math.min(input.get("cols").getAsInt(), 13));
        int rows = input.has("rows") && !input.get("rows").isJsonNull()
            ? Math.max(1, Math.min(input.get("rows").getAsInt(), 10)) : 2;

        MicropolisTool zoneTool;
        switch (zoneType) {
            case "residential": zoneTool = MicropolisTool.RESIDENTIAL; break;
            case "commercial": zoneTool = MicropolisTool.COMMERCIAL; break;
            case "industrial": zoneTool = MicropolisTool.INDUSTRIAL; break;
            default: return errorResult("Invalid zone_type. Use residential, commercial, or industrial.");
        }

        List<Integer> stripYPositions = new ArrayList<>();
        List<Integer> roadYPositions = new ArrayList<>();

        int curY = y;
        for (int i = 0; i < rows; i++) {
            stripYPositions.add(curY);
            curY += 3;
            if (i % 2 == 0) {
                roadYPositions.add(curY);
                curY += 1;
            }
        }

        int blockWidth = cols * 3;
        int blockHeight = curY - y;

        if (x < 1 || y < 1 || x + blockWidth >= 120 || curY >= 100) {
            return errorResult("Block " + blockWidth + "x" + blockHeight
                + " at (" + x + "," + y + ") needs 1-tile margin for perimeter roads."
                + " Valid range: x=1.." + (119 - blockWidth) + ", y=1.." + (99 - blockHeight) + "."
                + " Use find_empty_area(" + (blockWidth + 2) + "," + (blockHeight + 2) + ") to find a valid spot.");
        }

        int perimeterLength = 2 * (blockWidth + 2) + 2 * blockHeight;
        int estimatedCost = (rows * cols * 100) + (roadYPositions.size() * blockWidth * 10)
            + (roadYPositions.size() * 5) + (perimeterLength * 10);
        if (engine.getBudget().getTotalFunds() < estimatedCost) {
            return errorResult("Estimated cost ~$" + estimatedCost
                + " exceeds funds $" + engine.getBudget().getTotalFunds()
                + ". Try fewer cols/rows.");
        }

        int zonesPlaced = 0, zonesFailed = 0, roadTiles = 0, wires = 0;
        List<String> failures = new ArrayList<>();

        for (int stripY : stripYPositions) {
            for (int col = 0; col < cols; col++) {
                int zx = x + col * 3;
                ToolResult tr = placeZoneDirect(zoneTool, zx, stripY);
                if (tr == ToolResult.SUCCESS) {
                    zonesPlaced++;
                } else {
                    zonesFailed++;
                    failures.add("zone(" + zx + "," + stripY + "): " + tr.name());
                }
            }
        }

        // Internal roads between zone strips
        for (int roadY : roadYPositions) {
            ToolStroke stroke = MicropolisTool.ROADS.beginStroke(engine, x, roadY);
            stroke.dragTo(x + blockWidth - 1, roadY);
            ToolResult tr = stroke.apply();
            if (tr == ToolResult.SUCCESS) {
                roadTiles += blockWidth;
            } else {
                failures.add("road(y=" + roadY + "): " + tr.name());
            }

            int wireX = x + (blockWidth / 2);
            if (observer.forceWireCrossing(wireX, roadY) >= 0) wires++;
        }

        // Perimeter roads around the entire block for proper road network
        int perimRoads = 0;
        int topRoadY = y - 1;
        int bottomRoadY = curY;
        int leftRoadX = x - 1;
        int rightRoadX = x + blockWidth;

        // Top road
        if (topRoadY >= 0) {
            int rxStart = Math.max(0, leftRoadX);
            int rxEnd = Math.min(119, rightRoadX);
            ToolStroke ts = MicropolisTool.ROADS.beginStroke(engine, rxStart, topRoadY);
            ts.dragTo(rxEnd, topRoadY);
            if (ts.apply() == ToolResult.SUCCESS) {
                perimRoads += rxEnd - rxStart + 1;
                int wx = x + blockWidth / 2;
                if (observer.forceWireCrossing(wx, topRoadY) >= 0) wires++;
            }
        }
        // Bottom road
        if (bottomRoadY < 100) {
            int rxStart = Math.max(0, leftRoadX);
            int rxEnd = Math.min(119, rightRoadX);
            ToolStroke ts = MicropolisTool.ROADS.beginStroke(engine, rxStart, bottomRoadY);
            ts.dragTo(rxEnd, bottomRoadY);
            if (ts.apply() == ToolResult.SUCCESS) {
                perimRoads += rxEnd - rxStart + 1;
                int wx = x + blockWidth / 2;
                if (observer.forceWireCrossing(wx, bottomRoadY) >= 0) wires++;
            }
        }
        // Left road
        if (leftRoadX >= 0) {
            int ryStart = Math.max(0, topRoadY);
            int ryEnd = Math.min(99, bottomRoadY);
            ToolStroke ts = MicropolisTool.ROADS.beginStroke(engine, leftRoadX, ryStart);
            ts.dragTo(leftRoadX, ryEnd);
            if (ts.apply() == ToolResult.SUCCESS) {
                perimRoads += ryEnd - ryStart + 1;
            }
        }
        // Right road
        if (rightRoadX < 120) {
            int ryStart = Math.max(0, topRoadY);
            int ryEnd = Math.min(99, bottomRoadY);
            ToolStroke ts = MicropolisTool.ROADS.beginStroke(engine, rightRoadX, ryStart);
            ts.dragTo(rightRoadX, ryEnd);
            if (ts.apply() == ToolResult.SUCCESS) {
                perimRoads += ryEnd - ryStart + 1;
            }
        }

        roadTiles += perimRoads;

        JsonObject r = new JsonObject();
        r.addProperty("success", zonesFailed == 0);
        r.addProperty("zones_placed", zonesPlaced);
        r.addProperty("zones_failed", zonesFailed);
        r.addProperty("road_tiles_internal", roadTiles - perimRoads);
        r.addProperty("road_tiles_perimeter", perimRoads);
        r.addProperty("road_tiles_total", roadTiles);
        r.addProperty("power_wires", wires);
        r.addProperty("block_size", blockWidth + "x" + blockHeight + " tiles");
        r.addProperty("block_position", "(" + x + "," + y + ") to (" + (x + blockWidth - 1) + "," + (curY - 1) + ")");
        r.addProperty("road_perimeter", "(" + leftRoadX + "," + topRoadY + ") to (" + rightRoadX + "," + bottomRoadY + ")");
        r.addProperty("funds_remaining", engine.getBudget().getTotalFunds());

        StringBuilder layout = new StringBuilder();
        for (int i = 0; i < stripYPositions.size(); i++) {
            int sy = stripYPositions.get(i);
            layout.append("strip ").append(i).append(": y=").append(sy).append("-").append(sy + 2);
            if (i < stripYPositions.size() - 1) layout.append(", ");
        }
        layout.append(" | internal roads: ");
        for (int i = 0; i < roadYPositions.size(); i++) {
            layout.append("y=").append(roadYPositions.get(i));
            if (i < roadYPositions.size() - 1) layout.append(", ");
        }
        layout.append(" | perimeter: top=").append(topRoadY)
              .append(" bottom=").append(bottomRoadY)
              .append(" left=").append(leftRoadX)
              .append(" right=").append(rightRoadX);
        r.addProperty("layout", layout.toString());

        if (!failures.isEmpty()) {
            JsonArray failArr = new JsonArray();
            int show = Math.min(failures.size(), 10);
            for (int i = 0; i < show; i++) failArr.add(failures.get(i));
            if (failures.size() > show) failArr.add("... and " + (failures.size() - show) + " more");
            r.add("failures", failArr);
        }

        r.addProperty("next_step", "Connect a power plant to any zone in this block via power line or use connect_power. "
            + "Power will propagate through all touching zones; wires already bridge the road rows. "
            + "The block has perimeter roads on all 4 sides for proper network connectivity.");

        return r;
    }

    private ToolResult placeZoneDirect(MicropolisTool tool, int x, int y) {
        int engineX = x + 1;
        int engineY = y + 1;
        ToolStroke stroke = tool.beginStroke(engine, engineX, engineY);
        stroke.dragTo(engineX, engineY);
        return stroke.apply();
    }

    private JsonObject executeReadStrategyGuide() {
        try {
            Path p = Paths.get(STRATEGY_FILE);
            if (!Files.exists(p)) {
                JsonObject r = new JsonObject();
                r.addProperty("strategy_guide", "No strategy guide found. The file game_strategy_guide.md is missing from the project root.");
                return r;
            }
            String content = new String(Files.readAllBytes(p));
            JsonObject r = new JsonObject();
            r.addProperty("strategy_guide", content);
            return r;
        } catch (IOException e) {
            return errorResult("Failed to read strategy guide: " + e.getMessage());
        }
    }

    private JsonObject executeStrategicPlan() {
        JsonObject r = new JsonObject();

        int pop = engine.getCityPopulation();
        int resPop = engine.getResPop();
        int comPop = engine.getComPop();
        int indPop = engine.getIndPop();
        int funds = engine.getBudget().getTotalFunds();
        int score = engine.getEvaluation().getCityScore();
        int resValve = engine.getResValve();
        int comValve = engine.getComValve();
        int indValve = engine.getIndValve();
        int poweredZones = engine.getPoweredZoneCount();
        int unpoweredZones = engine.getUnpoweredZoneCount();
        int crime = engine.getCrimeAverage();
        int pollution = engine.getPollutionAverage();
        int traffic = engine.getTrafficAverage();
        int roadEffect = engine.getRoadEffect();
        int policeEffect = engine.getPoliceEffect();
        int fireEffect = engine.getFireEffect();
        boolean resCap = engine.isResCap();
        boolean comCap = engine.isComCap();
        boolean indCap = engine.isIndCap();
        int totalZones = poweredZones + unpoweredZones;

        String phase;
        if (totalZones == 0) {
            phase = "BOOTSTRAP";
        } else if (pop < 2000) {
            phase = "EARLY";
        } else if (pop < 10000) {
            phase = "MID";
        } else {
            phase = "LATE";
        }
        r.addProperty("game_phase", phase);

        JsonObject metrics = new JsonObject();
        metrics.addProperty("population", pop);
        metrics.addProperty("funds", funds);
        metrics.addProperty("score", score);
        metrics.addProperty("total_zones", totalZones);
        metrics.addProperty("powered_zones", poweredZones);
        metrics.addProperty("unpowered_zones", unpoweredZones);
        metrics.addProperty("res_valve", resValve);
        metrics.addProperty("com_valve", comValve);
        metrics.addProperty("ind_valve", indValve);
        metrics.addProperty("crime_avg", crime);
        metrics.addProperty("pollution_avg", pollution);
        metrics.addProperty("traffic_avg", traffic);
        metrics.addProperty("road_effect", roadEffect);
        metrics.addProperty("police_effect", policeEffect);
        metrics.addProperty("fire_effect", fireEffect);
        r.add("current_metrics", metrics);

        JsonArray priorities = new JsonArray();
        JsonArray objectives = new JsonArray();
        int objCount = 0;

        // P0: Bootstrap — no zones at all
        if (totalZones == 0) {
            JsonObject p = new JsonObject();
            p.addProperty("priority", 0);
            p.addProperty("issue", "City has no zones — need to bootstrap");
            p.addProperty("strategy_ref", "Priority Order: Power first, then road access, then zones");
            priorities.add(p);

            if (funds >= 5000) {
                addObjective(objectives, ++objCount,
                    "Build nuclear power plant at map edge",
                    "find_empty_area(4,4) → place_building(nuclear, x, y)",
                    5000, "get_infrastructure shows 1 nuclear plant");
            } else if (funds >= 3000) {
                addObjective(objectives, ++objCount,
                    "Build coal power plant at map edge",
                    "find_empty_area(4,4) → place_building(powerplant, x, y)",
                    3000, "get_infrastructure shows 1 coal plant");
            }

            int blockCost = Math.min(funds - 5000, 2000);
            if (blockCost >= 800) {
                addObjective(objectives, ++objCount,
                    "Build first residential neighborhood near power plant",
                    "plan_city_block(residential, x, y, 3, 2) → connect_power(x, y)",
                    800, "get_city_entities shows residential zones, all powered");
                addObjective(objectives, ++objCount,
                    "Build first industrial zone at map edge (away from residential)",
                    "plan_city_block(industrial, x, y, 2, 2) → connect_power(x, y)",
                    600, "get_city_entities shows industrial zones, all powered");
            }
        }

        // P1: Unpowered zones — critical
        if (unpoweredZones > 0) {
            JsonObject p = new JsonObject();
            p.addProperty("priority", 1);
            p.addProperty("issue", unpoweredZones + " unpowered zones — zscore=-500, no growth possible");
            p.addProperty("strategy_ref", "Power → Growth: Without power, zscore=-500. Nothing else matters.");
            priorities.add(p);

            if (objCount < 5) {
                addObjective(objectives, ++objCount,
                    "Connect all " + unpoweredZones + " unpowered zones to the power grid",
                    "get_city_entities (find unpowered coords) → connect_power(x, y) for each",
                    unpoweredZones * 20, "get_city_entities shows unpowered_zones=0");
            }
        }

        // P2: Road effect below max
        if (roadEffect < 32 && totalZones > 0) {
            JsonObject p = new JsonObject();
            p.addProperty("priority", 2);
            p.addProperty("issue", "roadEffect=" + roadEffect + "/32 — directly subtracts from score");
            p.addProperty("strategy_ref", "Fund roads to roadEffect=32. Below this, direct score subtraction.");
            priorities.add(p);

            if (objCount < 5) {
                addObjective(objectives, ++objCount,
                    "Fix road funding: set road maintenance to 100%",
                    "set_budget(100, police_pct, fire_pct)",
                    0, "get_averages shows road_effect=32");
            }
        }

        // P3: Growth caps approaching or active
        if (resCap && objCount < 5) {
            JsonObject p = new JsonObject();
            p.addProperty("priority", 3);
            p.addProperty("issue", "Residential growth CAPPED — no stadium (resPop=" + resPop + ">500). Score penalty -15%");
            p.addProperty("strategy_ref", "Build Stadium when resPop > 500. Each cap = -15% score.");
            priorities.add(p);
            addObjective(objectives, ++objCount,
                "Build stadium to lift residential growth cap",
                "find_empty_area(4,4) → place_building(stadium, x, y) → connect_power(x, y)",
                5000, "get_demand shows res_capped=false");
        }
        if (indCap && objCount < 5) {
            JsonObject p = new JsonObject();
            p.addProperty("priority", 3);
            p.addProperty("issue", "Industrial growth CAPPED — no seaport (indPop=" + indPop + ">70). Score penalty -15%");
            priorities.add(p);
            addObjective(objectives, ++objCount,
                "Build seaport to lift industrial growth cap",
                "find_empty_area(4,4) near water → place_building(seaport, x, y)",
                3000, "get_demand shows ind_capped=false");
        }
        if (comCap && objCount < 5) {
            JsonObject p = new JsonObject();
            p.addProperty("priority", 3);
            p.addProperty("issue", "Commercial growth CAPPED — no airport (comPop=" + comPop + ">100). Score penalty -15%");
            priorities.add(p);
            addObjective(objectives, ++objCount,
                "Build airport to lift commercial growth cap",
                "find_empty_area(6,6) → place_building(airport, x, y) → connect_power(x, y)",
                10000, "get_demand shows com_capped=false");
        }

        // Approaching caps (not yet active)
        if (!resCap && resPop > 400 && resPop <= 500 && objCount < 5) {
            addObjective(objectives, ++objCount,
                "Build stadium SOON — resPop=" + resPop + ", cap activates at 500",
                "find_empty_area(4,4) → place_building(stadium, x, y)",
                5000, "get_infrastructure shows stadium count >= 1");
        }
        if (!indCap && indPop > 55 && indPop <= 70 && objCount < 5) {
            addObjective(objectives, ++objCount,
                "Build seaport SOON — indPop=" + indPop + ", cap activates at 70",
                "find_empty_area(4,4) near water → place_building(seaport, x, y)",
                3000, "get_infrastructure shows seaport count >= 1");
        }
        if (!comCap && comPop > 80 && comPop <= 100 && objCount < 5) {
            addObjective(objectives, ++objCount,
                "Build airport SOON — comPop=" + comPop + ", cap activates at 100",
                "find_empty_area(6,6) → place_building(airport, x, y)",
                10000, "get_infrastructure shows airport count >= 1");
        }

        // P4: High crime without police
        if (crime > 80 && policeEffect < 500 && objCount < 5) {
            JsonObject p = new JsonObject();
            p.addProperty("priority", 4);
            p.addProperty("issue", "High crime (" + crime + ") with low police effect (" + policeEffect + ")");
            p.addProperty("strategy_ref", "Crime = 128 - landValue + popDensity - policeEffect. Police stations every ~15 tiles.");
            priorities.add(p);
            addObjective(objectives, ++objCount,
                "Build police station in high-crime area to reduce crime",
                "get_city_entities (find dense area) → find_empty_area(3,3) → place_building(police, x, y) → connect_power(x, y)",
                500, "get_averages shows crime_avg decreased or policeEffect increased");
        }

        // P5: High pollution
        if (pollution > 60 && objCount < 5) {
            JsonObject p = new JsonObject();
            p.addProperty("priority", 5);
            p.addProperty("issue", "High pollution (" + pollution + ") — triggers monster attacks at >60, destroys land value");
            p.addProperty("strategy_ref", "Pollution → LandValue → Crime chain. Separate industry. Nuclear > Coal.");
            priorities.add(p);
            addObjective(objectives, ++objCount,
                "Reduce pollution: separate industrial from residential, add parks as buffers",
                "render_map to find industrial near residential → place_park(x,y) between them",
                100, "get_averages shows pollution_avg < 60");
        }

        // P6: Demand-based expansion
        if (objCount < 5 && totalZones > 0) {
            int maxValve = Math.max(resValve, Math.max(comValve, indValve));
            if (maxValve > 0) {
                String zoneType;
                int valve;
                if (resValve >= comValve && resValve >= indValve) {
                    zoneType = "residential"; valve = resValve;
                } else if (comValve >= indValve) {
                    zoneType = "commercial"; valve = comValve;
                } else {
                    zoneType = "industrial"; valve = indValve;
                }

                JsonObject p = new JsonObject();
                p.addProperty("priority", 6);
                p.addProperty("issue", "Positive " + zoneType + " demand (valve=" + valve + ") — city wants growth");
                p.addProperty("strategy_ref", "Build the zone type with highest positive valve. Check analyze_demand first.");
                priorities.add(p);

                String location = "residential".equals(zoneType) ? "near city center (highest land value)"
                    : "industrial".equals(zoneType) ? "at map edge (pollution spills off)"
                    : "near city center (comRate = 64 - distance/4)";

                addObjective(objectives, ++objCount,
                    "Expand " + zoneType + " zones " + location + " (demand=" + valve + ")",
                    "analyze_demand → find_empty_area → plan_city_block(" + zoneType + ", x, y, 3, 2) → connect_power(x, y)",
                    800, "get_demand shows " + zoneType.substring(0, 3) + "_valve decreased (zones absorbing demand)");
            } else if (maxValve < -500) {
                JsonObject p = new JsonObject();
                p.addProperty("priority", 6);
                p.addProperty("issue", "All demand valves negative — city is oversupplied or taxes too high");
                p.addProperty("strategy_ref", "Negative valves: lower taxes or wait. Don't build zones with negative demand.");
                priorities.add(p);

                if (engine.getCityTax() > 5 && objCount < 5) {
                    addObjective(objectives, ++objCount,
                        "Lower tax rate to stimulate demand (current: " + engine.getCityTax() + "%)",
                        "set_tax_rate(" + Math.max(4, engine.getCityTax() - 2) + ")",
                        0, "get_demand shows valves trending positive after 1-2 turns");
                }
            }
        }

        // P7: Low fire coverage
        if (fireEffect < 500 && pop > 1000 && objCount < 5) {
            addObjective(objectives, ++objCount,
                "Build fire station for safety coverage",
                "find_empty_area(3,3) near developed area → place_building(fire, x, y) → connect_power(x, y)",
                500, "get_averages shows fire_effect increased");
        }

        // P8: Score optimization for late game
        if ("LATE".equals(phase) && objCount < 5) {
            if (score < 500) {
                JsonObject p = new JsonObject();
                p.addProperty("priority", 8);
                p.addProperty("issue", "Score=" + score + " is below 500 — focus on score optimization");
                p.addProperty("strategy_ref", "Score Optimization Checklist: power all zones, build caps, fund services, grow pop, minimize problems.");
                priorities.add(p);
            }
            if (policeEffect < 1000 && objCount < 5) {
                addObjective(objectives, ++objCount,
                    "Maximize police funding and coverage for score multiplier",
                    "set_budget(100, 100, fire_pct) + place more police stations if needed",
                    500, "get_averages shows police_effect approaching 1000");
            }
        }

        // Fill remaining slots with phase-appropriate objectives
        if (objCount == 0) {
            addObjective(objectives, ++objCount,
                "City is stable — use capture_map_screenshot to review layout, then expand smartly",
                "capture_map_screenshot → analyze_demand → plan_city_block for highest demand zone type",
                800, "Population or score increased after expansion");
        }

        r.add("priority_analysis", priorities);
        r.add("suggested_objectives", objectives);
        r.addProperty("objective_count", objCount);

        StringBuilder instructions = new StringBuilder();
        instructions.append("Call set_objectives with these objectives (use semicolons to separate). ");
        instructions.append("Execute each objective using the listed tools. ");
        instructions.append("Before calling complete_objective, VERIFY using the listed verification check. ");
        instructions.append("When all objectives are done, call strategic_plan again for next priorities.");
        r.addProperty("instructions", instructions.toString());

        return r;
    }

    private void addObjective(JsonArray objectives, int index, String description,
                               String tools, int estimatedCost, String verification) {
        JsonObject obj = new JsonObject();
        obj.addProperty("index", index);
        obj.addProperty("objective", description);
        obj.addProperty("tools_to_use", tools);
        obj.addProperty("estimated_cost", "$" + estimatedCost);
        obj.addProperty("verify_with", verification);
        objectives.add(obj);
    }

    private JsonObject executeSetObjectives(JsonObject input) {
        String raw = input.get("objectives").getAsString();
        String[] parts = raw.split(";");
        List<String> objectives = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) objectives.add(trimmed);
        }
        if (objectives.isEmpty()) {
            return errorResult("Provide at least one objective.");
        }

        List<List<String>> linkedIds = null;
        if (input.has("linked_messages") && !input.get("linked_messages").isJsonNull()) {
            String linkedRaw = input.get("linked_messages").getAsString();
            String[] linkedParts = linkedRaw.split(";", -1);
            linkedIds = new ArrayList<>();
            for (String group : linkedParts) {
                List<String> ids = new ArrayList<>();
                for (String id : group.split(",")) {
                    String trimmed = id.trim();
                    if (!trimmed.isEmpty()) ids.add(trimmed);
                }
                linkedIds.add(ids);
            }
        }

        assistant.setObjectives(objectives, linkedIds);
        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("active_count", objectives.size());
        JsonArray arr = new JsonArray();
        List<AIAssistant.Objective> objs = assistant.getObjectives();
        for (AIAssistant.Objective o : objs) {
            JsonObject objJson = new JsonObject();
            objJson.addProperty("text", o.getText());
            if (!o.getLinkedMessageIds().isEmpty()) {
                objJson.addProperty("linked_messages", String.join(",", o.getLinkedMessageIds()));
            }
            arr.add(objJson);
        }
        r.add("objectives", arr);
        return r;
    }

    private JsonObject executeCompleteObjective(JsonObject input) {
        int index = input.get("index").getAsInt();
        String verification = input.has("verification") && !input.get("verification").isJsonNull()
            ? input.get("verification").getAsString().trim() : "";

        int zeroBasedIndex = index - 1;
        List<AIAssistant.Objective> current = assistant.getObjectives();
        if (zeroBasedIndex < 0 || zeroBasedIndex >= current.size()) {
            return errorResult("Invalid index " + index + ". You have " + current.size() + " active objectives (1-" + current.size() + ").");
        }

        if (verification.isEmpty() || verification.length() < 15) {
            return errorResult("Verification required. Explain HOW you confirmed this objective is complete. "
                + "Reference a specific tool check (e.g. 'get_city_entities shows 0 unpowered zones'). "
                + "You must VERIFY before completing — don't guess.");
        }

        String completedText = current.get(zeroBasedIndex).getText();
        assistant.completeObjective(zeroBasedIndex);

        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("completed", completedText);
        r.addProperty("verification", verification);
        r.addProperty("remaining_active", assistant.getObjectives().size());

        JsonObject metrics = new JsonObject();
        metrics.addProperty("population", engine.getCityPopulation());
        metrics.addProperty("score", engine.getEvaluation().getCityScore());
        metrics.addProperty("funds", engine.getBudget().getTotalFunds());
        metrics.addProperty("powered_zones", engine.getPoweredZoneCount());
        metrics.addProperty("unpowered_zones", engine.getUnpoweredZoneCount());
        metrics.addProperty("crime", engine.getCrimeAverage());
        metrics.addProperty("pollution", engine.getPollutionAverage());
        r.add("current_metrics", metrics);

        if (assistant.getObjectives().isEmpty()) {
            r.addProperty("hint", "All objectives completed! Call strategic_plan to generate your next set of objectives.");
        }
        return r;
    }

    private JsonObject executeGetObjectives() {
        List<AIAssistant.Objective> active = assistant.getObjectives();
        List<AIAssistant.Objective> completed = assistant.getCompletedObjectives();
        JsonObject r = new JsonObject();
        JsonArray activeArr = new JsonArray();
        for (AIAssistant.Objective o : active) {
            JsonObject obj = new JsonObject();
            obj.addProperty("text", o.getText());
            if (!o.getLinkedMessageIds().isEmpty()) {
                obj.addProperty("covers_messages", String.join(",", o.getLinkedMessageIds()));
            }
            activeArr.add(obj);
        }
        r.add("active", activeArr);
        r.addProperty("active_count", active.size());
        JsonArray completedArr = new JsonArray();
        for (AIAssistant.Objective o : completed) {
            JsonObject obj = new JsonObject();
            obj.addProperty("text", o.getText());
            if (!o.getLinkedMessageIds().isEmpty()) {
                obj.addProperty("covers_messages", String.join(",", o.getLinkedMessageIds()));
            }
            completedArr.add(obj);
        }
        r.add("completed", completedArr);
        r.addProperty("completed_count", completed.size());
        if (active.isEmpty() && completed.isEmpty()) {
            r.addProperty("message", "No objectives set. Use set_objectives to define your short-term goals.");
        }
        return r;
    }

    private JsonObject executeWriteSessionNote(JsonObject input) {
        String note = input.get("note").getAsString();
        try {
            Path dir = Paths.get(AI_DATA_DIR);
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path p = Paths.get(SESSION_FILE);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String entry = "[" + timestamp + "] " + note + System.lineSeparator();
            Files.write(p, entry.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            JsonObject r = new JsonObject();
            r.addProperty("success", true);
            r.addProperty("message", "Session note recorded.");
            return r;
        } catch (IOException e) {
            return errorResult("Failed to write session note: " + e.getMessage());
        }
    }

    private JsonObject executeReadSessionNotes() {
        try {
            Path p = Paths.get(SESSION_FILE);
            if (!Files.exists(p)) {
                JsonObject r = new JsonObject();
                r.addProperty("notes", "No session notes yet. Use write_session_note to record map-specific observations.");
                return r;
            }
            String content = new String(Files.readAllBytes(p));
            JsonObject r = new JsonObject();
            r.addProperty("notes", content);
            return r;
        } catch (IOException e) {
            return errorResult("Failed to read session notes: " + e.getMessage());
        }
    }

    private JsonObject executeCaptureMapScreenshot() {
        String base64 = MapScreenshot.captureBase64(engine);
        if (base64 == null) {
            return errorResult("Failed to capture map screenshot.");
        }
        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("message", "Map screenshot captured (960x800 PNG). The image shows the full 120x100 tile map.");
        r.addProperty("_image_base64", base64);
        r.addProperty("_image_media_type", "image/png");
        return r;
    }

    private JsonObject executeDismissBudget() {
        JsonObject r = new JsonObject();
        if (assistant.isBudgetDialogOpen()) {
            boolean dismissed = assistant.dismissBudgetDialog();
            r.addProperty("success", dismissed);
            r.addProperty("message", dismissed ? "Budget dialog closed. Game resumed." : "Failed to dismiss budget dialog.");
        } else {
            r.addProperty("success", true);
            r.addProperty("message", "No budget dialog is currently open.");
        }
        return r;
    }

    private JsonObject executeEndTurn(JsonObject input) {
        String action = input.get("action").getAsString().toLowerCase().trim();
        JsonObject r = new JsonObject();
        if ("continue".equals(action)) {
            assistant.setNextTurnDelayMs(0);
            r.addProperty("success", true);
            r.addProperty("next_turn", "immediate");
        } else {
            assistant.setNextTurnDelayMs(10_000);
            r.addProperty("success", true);
            r.addProperty("next_turn", "wait_10s");
        }
        return r;
    }

    // ── Source Code Introspection ──────────────────────────────────────

    private JsonObject executeSearchEngineCode(JsonObject input) {
        String query = input.get("query").getAsString();
        String scope = input.has("scope") && !input.get("scope").isJsonNull()
                ? input.get("scope").getAsString() : "engine";

        Path baseSrc = Paths.get("src", "main", "java", "micropolisj");
        List<Path> searchDirs = new ArrayList<>();
        switch (scope.toLowerCase()) {
            case "gui":    searchDirs.add(baseSrc.resolve("gui")); break;
            case "ai":     searchDirs.add(baseSrc.resolve("ai")); break;
            case "all":
                searchDirs.add(baseSrc.resolve("engine"));
                searchDirs.add(baseSrc.resolve("gui"));
                searchDirs.add(baseSrc.resolve("ai"));
                break;
            default:       searchDirs.add(baseSrc.resolve("engine")); break;
        }

        JsonArray matches = new JsonArray();
        int totalMatches = 0;
        int maxResults = 30;
        String lowerQuery = query.toLowerCase();

        try {
            for (Path dir : searchDirs) {
                if (!Files.exists(dir)) continue;
                File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".java"));
                if (files == null) continue;
                Arrays.sort(files);
                for (File file : files) {
                    List<String> lines = Files.readAllLines(file.toPath());
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).toLowerCase().contains(lowerQuery)) {
                            totalMatches++;
                            if (matches.size() < maxResults) {
                                JsonObject match = new JsonObject();
                                match.addProperty("file", file.getName());
                                match.addProperty("line", i + 1);
                                int ctxStart = Math.max(0, i - 2);
                                int ctxEnd = Math.min(lines.size() - 1, i + 2);
                                StringBuilder ctx = new StringBuilder();
                                for (int j = ctxStart; j <= ctxEnd; j++) {
                                    String prefix = (j == i) ? ">>>" : "   ";
                                    ctx.append(prefix).append(" ").append(j + 1).append(": ")
                                       .append(lines.get(j)).append("\n");
                                }
                                match.addProperty("context", ctx.toString().trim());
                                matches.add(match);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            return errorResult("Failed to search source code: " + e.getMessage());
        }

        JsonObject result = new JsonObject();
        result.addProperty("query", query);
        result.addProperty("scope", scope);
        result.addProperty("total_matches", totalMatches);
        result.addProperty("shown", matches.size());
        result.add("matches", matches);
        if (totalMatches == 0) {
            result.addProperty("suggestion",
                "No matches. Try related terms. Common keywords: crime, pollution, traffic, "
                + "growth, demand, tax, power, score, budget, fire, police, landValue, density, "
                + "zone, residential, commercial, industrial, valves, overlay, ramp");
        }
        return result;
    }

    private JsonObject executeReadEngineFile(JsonObject input) {
        String className = input.get("class_name").getAsString();
        String methodName = input.has("method") && !input.get("method").isJsonNull()
                ? input.get("method").getAsString() : null;
        int startLine = input.has("start_line") ? input.get("start_line").getAsInt() : -1;
        int endLine = input.has("end_line") ? input.get("end_line").getAsInt() : -1;

        if (!className.endsWith(".java")) className += ".java";

        Path baseSrc = Paths.get("src", "main", "java", "micropolisj");
        String[] subdirs = {"engine", "gui", "ai"};
        Path filePath = null;
        for (String sub : subdirs) {
            Path candidate = baseSrc.resolve(sub).resolve(className);
            if (Files.exists(candidate)) { filePath = candidate; break; }
        }
        if (filePath == null) {
            String target = className;
            for (String sub : subdirs) {
                Path dir = baseSrc.resolve(sub);
                if (!Files.exists(dir)) continue;
                File[] found = dir.toFile().listFiles((d, n) -> n.equalsIgnoreCase(target));
                if (found != null && found.length > 0) { filePath = found[0].toPath(); break; }
            }
        }
        if (filePath == null) {
            JsonObject r = new JsonObject();
            r.addProperty("error", "Class not found: " + className.replace(".java", ""));
            JsonArray available = new JsonArray();
            for (String sub : subdirs) {
                Path dir = baseSrc.resolve(sub);
                if (!Files.exists(dir)) continue;
                File[] files = dir.toFile().listFiles((d, n) -> n.endsWith(".java"));
                if (files != null) {
                    Arrays.sort(files);
                    for (File f : files) available.add(sub + "/" + f.getName().replace(".java", ""));
                }
            }
            r.add("available_classes", available);
            return r;
        }

        try {
            List<String> lines = Files.readAllLines(filePath);

            if (methodName != null && !methodName.isEmpty()) {
                return extractMethodFromSource(lines, methodName, filePath.getFileName().toString());
            }

            if (startLine > 0) {
                if (endLine <= 0) endLine = startLine + 100;
                int start = Math.max(0, startLine - 1);
                int end = Math.min(lines.size(), endLine);
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < end; i++) {
                    sb.append(i + 1).append(": ").append(lines.get(i)).append("\n");
                }
                JsonObject r = new JsonObject();
                r.addProperty("file", filePath.getFileName().toString());
                r.addProperty("lines", (start + 1) + "-" + end);
                r.addProperty("total_lines", lines.size());
                r.addProperty("content", sb.toString());
                return r;
            }

            int maxLines = 150;
            int end = Math.min(lines.size(), maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < end; i++) {
                sb.append(i + 1).append(": ").append(lines.get(i)).append("\n");
            }

            JsonObject r = new JsonObject();
            r.addProperty("file", filePath.getFileName().toString());
            r.addProperty("total_lines", lines.size());
            r.addProperty("shown_lines", end);
            r.addProperty("content", sb.toString());
            if (lines.size() > maxLines) {
                r.addProperty("truncated", true);
                r.addProperty("hint", "File has " + lines.size() + " lines. Use 'method' to extract a specific method, or 'start_line'/'end_line' for a range.");
            }

            JsonArray members = new JsonArray();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (looksLikeMethodSignature(line)) {
                    members.add((i + 1) + ": " + line.replaceAll("\\{\\s*$", "").trim());
                }
            }
            if (members.size() > 0) r.add("members", members);
            return r;

        } catch (IOException e) {
            return errorResult("Failed to read file: " + e.getMessage());
        }
    }

    private JsonObject extractMethodFromSource(List<String> lines, String methodName, String fileName) {
        int methodStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("//") || line.trim().startsWith("*")) continue;
            if ((line.contains(methodName + "(") || line.contains(methodName + " ("))
                    && !line.trim().startsWith("//") && !line.trim().startsWith("*")) {
                methodStart = i;
                break;
            }
        }
        if (methodStart < 0) {
            JsonObject r = new JsonObject();
            r.addProperty("error", "Method '" + methodName + "' not found in " + fileName);
            JsonArray suggestions = new JsonArray();
            String lower = methodName.toLowerCase();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.toLowerCase().contains(lower) && line.contains("(")
                        && !line.startsWith("//") && !line.startsWith("*")) {
                    suggestions.add((i + 1) + ": " + line.replaceAll("\\{\\s*$", "").trim());
                }
            }
            if (suggestions.size() > 0) r.add("similar", suggestions);
            return r;
        }

        int braceCount = 0;
        boolean foundBrace = false;
        int methodEnd = methodStart;
        for (int i = methodStart; i < lines.size(); i++) {
            for (char c : lines.get(i).toCharArray()) {
                if (c == '{') { braceCount++; foundBrace = true; }
                if (c == '}') braceCount--;
            }
            if (foundBrace && braceCount == 0) { methodEnd = i; break; }
        }

        int contextStart = methodStart;
        for (int i = methodStart - 1; i >= Math.max(0, methodStart - 15); i--) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("/**") || trimmed.startsWith("*") || trimmed.startsWith("@")
                    || trimmed.startsWith("//")) {
                contextStart = i;
            } else {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = contextStart; i <= methodEnd; i++) {
            sb.append(i + 1).append(": ").append(lines.get(i)).append("\n");
        }

        JsonObject r = new JsonObject();
        r.addProperty("file", fileName);
        r.addProperty("method", methodName);
        r.addProperty("start_line", contextStart + 1);
        r.addProperty("end_line", methodEnd + 1);
        r.addProperty("content", sb.toString());
        return r;
    }

    private boolean looksLikeMethodSignature(String line) {
        if (line.isEmpty() || line.startsWith("//") || line.startsWith("*")
                || line.startsWith("/*") || line.startsWith("import") || line.startsWith("package")) {
            return false;
        }
        return (line.startsWith("public ") || line.startsWith("private ") || line.startsWith("protected ")
                || line.startsWith("static ") || line.startsWith("void ") || line.startsWith("abstract ")
                || line.startsWith("synchronized ") || line.startsWith("final "))
                && line.contains("(");
    }

    private JsonObject errorResult(String message) {
        JsonObject r = new JsonObject();
        r.addProperty("success", false);
        r.addProperty("error", message);
        return r;
    }

    private JsonObject makeTool(String name, String description, String[]... params) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (String[] p : params) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", p[1]);
            prop.addProperty("description", p[2]);
            properties.add(p[0], prop);
            required.add(p[0]);
        }
        inputSchema.add("properties", properties);
        inputSchema.add("required", required);
        tool.add("input_schema", inputSchema);
        return tool;
    }

    private JsonObject makeToolWithOptional(String name, String description,
            String[][] requiredParams, String[][] optionalParams) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (String[] p : requiredParams) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", p[1]);
            prop.addProperty("description", p[2]);
            properties.add(p[0], prop);
            required.add(p[0]);
        }
        for (String[] p : optionalParams) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", p[1]);
            prop.addProperty("description", p[2]);
            properties.add(p[0], prop);
        }
        inputSchema.add("properties", properties);
        inputSchema.add("required", required);
        tool.add("input_schema", inputSchema);
        return tool;
    }

    private String[] param(String name, String type, String description) {
        return new String[]{name, type, description};
    }
}
