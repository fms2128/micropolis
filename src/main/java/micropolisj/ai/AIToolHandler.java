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

    private final Micropolis engine;
    private final GameStateObserver observer;

    public AIToolHandler(Micropolis engine, GameStateObserver observer) {
        this.engine = engine;
        this.observer = observer;
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
        tools.add(makeTool("get_budget_details",
            "Get detailed budget breakdown including tax income, funding requests and actual funding for each department."
        ));
        tools.add(makeTool("find_empty_area",
            "Scan the entire map to find a contiguous rectangular area of empty (dirt) tiles. Returns the top-left corner of the best area found, preferring areas near existing roads. Use BEFORE placing buildings/zones to find valid locations. For zones use 3x3, for powerplant/stadium/seaport use 4x4, for airport use 6x6.",
            param("width", "integer", "Required width in tiles (e.g. 3 for zones, 4 for powerplant, 6 for airport)"),
            param("height", "integer", "Required height in tiles (e.g. 3 for zones, 4 for powerplant, 6 for airport)")
        ));
        tools.add(makeTool("write_learning",
            "Record a learning or observation about what works or doesn't work in the game. These notes persist across turns and help you avoid repeating mistakes. Write down: what you tried, what happened, and what to do differently. Be specific about coordinates, tile types, and tool behaviors.",
            param("category", "string", "Category: placement_rules, strategy, terrain, tools, bugs, tips"),
            param("observation", "string", "What you observed or learned. Be specific and actionable.")
        ));
        tools.add(makeTool("read_learnings",
            "Read all previously recorded learnings and observations. Call this at the start of each session or when you're stuck to review past insights."
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
                case "get_budget_details": return executeGetBudget();
                case "find_empty_area": return executeFindEmptyArea(input);
                case "write_learning": return executeWriteLearning(input);
                case "read_learnings": return executeReadLearnings();
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

    private JsonObject executeGetBudget() {
        return observer.getBudgetInfo();
    }

    private JsonObject applyTool(MicropolisTool tool, int x, int y) {
        if (!engine.testBounds(x, y)) return errorResult("Coordinates (" + x + "," + y + ") out of bounds. Map is 120x100.");

        ToolStroke stroke = tool.beginStroke(engine, x, y);
        stroke.dragTo(x, y);
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
