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

    private static final String LOG_FILE = "ai_learnings.log";
    private static final String MEMORY_FILE = "agent_memory.md";
    private static final String STRATEGY_FILE = "game_strategy_guide.md";

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
        tools.add(makeTool("find_empty_area",
            "Scan the entire map to find a contiguous rectangular area of empty (dirt) tiles. Returns the top-left corner of the best area found, preferring areas near existing roads. Use BEFORE placing buildings/zones to find valid locations. For zones use 3x3, for powerplant/stadium/seaport use 4x4, for airport use 6x6.",
            param("width", "integer", "Required width in tiles (e.g. 3 for zones, 4 for powerplant, 6 for airport)"),
            param("height", "integer", "Required height in tiles (e.g. 3 for zones, 4 for powerplant, 6 for airport)")
        ));
        tools.add(makeTool("write_learning",
            "Record a quick one-line observation to the learnings log. For structured long-term knowledge, prefer update_memory instead.",
            param("category", "string", "Category: placement_rules, strategy, terrain, tools, bugs, tips"),
            param("observation", "string", "What you observed or learned. Be specific and actionable.")
        ));
        tools.add(makeTool("read_learnings",
            "Read all previously recorded learnings from the log. For your structured cheatsheet, use read_memory instead."
        ));
        tools.add(makeTool("read_memory",
            "Read your long-term memory cheatsheet (agent_memory.md). This contains your accumulated knowledge about what strategies work, what doesn't, placement rules, financial tips, and your current best strategy. READ THIS at the start of every new game session and when the reward signal turns negative."
        ));
        tools.add(makeTool("read_strategy_guide",
            "Read the game strategy guide (game_strategy_guide.md). Contains exact formulas, thresholds, and causal relationships extracted from the engine source code: zone growth mechanics, demand valve calculations, scoring system, overlay systems, costs, and strategic implications. Read this when you need to understand WHY something is happening or to plan optimal build order."
        ));
        tools.add(makeTool("update_memory",
            "Update a section in your long-term memory cheatsheet. Use this to record durable strategic knowledge you've confirmed over multiple turns. This is your most valuable tool for getting better over time. Sections: 'Strategies That Work', 'Strategies That Don't Work', 'Placement Rules Learned', 'Financial Management', 'Common Mistakes', 'Map Reading Tips', 'Current Best Strategy'.",
            param("section", "string", "Section name exactly as listed (e.g. 'Strategies That Work')"),
            param("entry", "string", "The knowledge to add. Be specific and actionable (e.g. 'Placing 3 residential zones before any industrial leads to higher early growth')"),
            param("replace", "string", "Set to 'true' to replace the entire section content, 'false' to append a new bullet point. Default: false.")
        ));
        tools.add(makeTool("set_objectives",
            "Set your current short-term objectives (1-5 goals). These appear in every turn's context to keep you focused. When the situation changes, use this to set entirely new objectives. To mark an existing objective as done, use complete_objective instead.",
            param("objectives", "string", "Semicolon-separated list of 1-5 short-term objectives. E.g. 'Power all zones; Reach 500 population; Build residential away from industry'")
        ));
        tools.add(makeTool("complete_objective",
            "Mark an objective as completed by its 1-based index. The objective moves to the 'completed' list and remains visible in the UI. Use this when you've achieved a goal, then optionally set_objectives to add new ones.",
            param("index", "integer", "1-based index of the objective to mark as completed (as shown in [Current Objectives])")
        ));
        tools.add(makeTool("get_objectives",
            "Read your current short-term objectives and recently completed ones."
        ));
        return tools;
    }

    public JsonObject executeTool(String toolName, JsonObject input) {
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
                case "find_empty_area": return executeFindEmptyArea(input);
                case "write_learning": return executeWriteLearning(input);
                case "read_learnings": return executeReadLearnings();
                case "read_memory": return executeReadMemory();
                case "read_strategy_guide": return executeReadStrategyGuide();
                case "update_memory": return executeUpdateMemory(input);
                case "set_objectives": return executeSetObjectives(input);
                case "complete_objective": return executeCompleteObjective(input);
                case "get_objectives": return executeGetObjectives();
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
        return toolResultToJson(tr, tool.name() + " from (" + x1 + "," + y1 + ") to (" + x2 + "," + y2 + ")");
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

    private JsonObject executeFindEmptyArea(JsonObject input) {
        int w = input.get("width").getAsInt();
        int h = input.get("height").getAsInt();
        return observer.findEmptyArea(w, h);
    }

    private JsonObject executeWriteLearning(JsonObject input) {
        String category = input.get("category").getAsString();
        String observation = input.get("observation").getAsString();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String entry = "[" + timestamp + "] [" + category + "] " + observation;

        try {
            Files.write(Paths.get(LOG_FILE),
                (entry + System.lineSeparator()).getBytes(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            JsonObject r = new JsonObject();
            r.addProperty("success", true);
            r.addProperty("message", "Learning recorded: " + category);
            return r;
        } catch (IOException e) {
            return errorResult("Failed to write learning: " + e.getMessage());
        }
    }

    private JsonObject executeReadLearnings() {
        try {
            Path p = Paths.get(LOG_FILE);
            if (!Files.exists(p)) {
                JsonObject r = new JsonObject();
                r.addProperty("learnings", "No learnings recorded yet. Use write_learning to record observations.");
                return r;
            }
            String content = new String(Files.readAllBytes(p));
            JsonObject r = new JsonObject();
            r.addProperty("learnings", content);
            r.addProperty("count", content.split(System.lineSeparator()).length);
            return r;
        } catch (IOException e) {
            return errorResult("Failed to read learnings: " + e.getMessage());
        }
    }

    private JsonObject executeReadMemory() {
        try {
            Path p = Paths.get(MEMORY_FILE);
            if (!Files.exists(p)) {
                JsonObject r = new JsonObject();
                r.addProperty("memory", "No memory file found. It will be created when you first call update_memory.");
                return r;
            }
            String content = new String(Files.readAllBytes(p));
            JsonObject r = new JsonObject();
            r.addProperty("memory", content);
            return r;
        } catch (IOException e) {
            return errorResult("Failed to read memory: " + e.getMessage());
        }
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

    private JsonObject executeUpdateMemory(JsonObject input) {
        String section = input.get("section").getAsString();
        String entry = input.get("entry").getAsString();
        boolean replace = input.has("replace") && "true".equalsIgnoreCase(input.get("replace").getAsString());

        try {
            Path p = Paths.get(MEMORY_FILE);
            String content;
            if (Files.exists(p)) {
                content = new String(Files.readAllBytes(p));
            } else {
                content = "# Agent Memory - Micropolis AI Cheatsheet\n\n";
            }

            String sectionHeader = "## " + section;
            int headerIdx = content.indexOf(sectionHeader);

            if (headerIdx < 0) {
                content = content + "\n" + sectionHeader + "\n\n- " + entry + "\n";
            } else {
                int contentStart = content.indexOf('\n', headerIdx);
                if (contentStart < 0) contentStart = content.length();
                contentStart++;

                int nextSection = content.indexOf("\n## ", contentStart);
                if (nextSection < 0) nextSection = content.length();

                if (replace) {
                    String before = content.substring(0, contentStart);
                    String after = content.substring(nextSection);
                    content = before + "\n" + entry + "\n" + after;
                } else {
                    String sectionContent = content.substring(contentStart, nextSection);
                    String trimmed = sectionContent.trim();
                    String newEntry = "- " + entry;

                    String before = content.substring(0, contentStart);
                    String after = content.substring(nextSection);
                    if (trimmed.isEmpty()) {
                        content = before + "\n" + newEntry + "\n" + after;
                    } else {
                        content = before + sectionContent.stripTrailing() + "\n" + newEntry + "\n" + after;
                    }
                }
            }

            Files.write(p, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            JsonObject r = new JsonObject();
            r.addProperty("success", true);
            r.addProperty("message", "Memory updated: [" + section + "] " + (replace ? "replaced" : "appended"));
            return r;
        } catch (IOException e) {
            return errorResult("Failed to update memory: " + e.getMessage());
        }
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
        assistant.setObjectives(objectives);
        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("active_count", objectives.size());
        JsonArray arr = new JsonArray();
        for (String o : objectives) arr.add(o);
        r.add("objectives", arr);
        return r;
    }

    private JsonObject executeCompleteObjective(JsonObject input) {
        int index = input.get("index").getAsInt();
        int zeroBasedIndex = index - 1;
        List<AIAssistant.Objective> current = assistant.getObjectives();
        if (zeroBasedIndex < 0 || zeroBasedIndex >= current.size()) {
            return errorResult("Invalid index " + index + ". You have " + current.size() + " active objectives (1-" + current.size() + ").");
        }
        String completedText = current.get(zeroBasedIndex).getText();
        assistant.completeObjective(zeroBasedIndex);
        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("completed", completedText);
        r.addProperty("remaining_active", assistant.getObjectives().size());
        return r;
    }

    private JsonObject executeGetObjectives() {
        List<AIAssistant.Objective> active = assistant.getObjectives();
        List<AIAssistant.Objective> completed = assistant.getCompletedObjectives();
        JsonObject r = new JsonObject();
        JsonArray activeArr = new JsonArray();
        for (AIAssistant.Objective o : active) activeArr.add(o.getText());
        r.add("active", activeArr);
        r.addProperty("active_count", active.size());
        JsonArray completedArr = new JsonArray();
        for (AIAssistant.Objective o : completed) completedArr.add(o.getText());
        r.add("completed", completedArr);
        r.addProperty("completed_count", completed.size());
        if (active.isEmpty() && completed.isEmpty()) {
            r.addProperty("message", "No objectives set. Use set_objectives to define your short-term goals.");
        }
        return r;
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

    private String[] param(String name, String type, String description) {
        return new String[]{name, type, description};
    }
}
